"use server";
// Org portal (/portal/org/v1, realm portals): a maker uploads a bulk disbursement CSV, a checker
// of the same org approves it (maker-checker), mw-portal-api executes the rows as throttled
// child transactions, the result file lists each row; a service provider queries its txns.

import { bulkCsv, unique } from "@/lib/format";
import type { Outcome } from "@/lib/types";
import { Trace } from "@/lib/server/edge";
import { bearer } from "@/lib/server/tokens";

export type BulkState = Outcome | null;
const str = (f: FormData, k: string) => String(f.get(k) ?? "").trim();
const B = "/portal/org/v1/bulk/batches";
const ID = /^[\w-]{1,80}$/;

export async function bulk(_prev: BulkState, form: FormData): Promise<BulkState> {
  const t = new Trace();
  const op = str(form, "op");
  const user = str(form, "user");
  const id = str(form, "batchId");
  const out = (headline: string, data?: Record<string, unknown>): Outcome => ({ ok: !t.failed, headline, steps: t.steps, data });
  if (!/^[a-z0-9-]{3,64}$/.test(user)) return out("choose a portal user");
  try {
    const h = await bearer("portals", user);
    const needId = () => {
      if (!ID.test(id)) throw new Error("enter a batch id (Upload or List shows it)");
    };
    switch (op) {
      case "sample": {
        const rows = Math.min(Math.max(Number(str(form, "rows") || "5"), 1), 1000);
        return out(`sample file with ${rows} row(s)`, { csv: bulkCsv(rows, str(form, "taka") || "1.00", unique()) });
      }
      case "upload": {
        const csv = String(form.get("csv") ?? "");
        if (!csv.trim()) return out("paste or generate a CSV first");
        const r = await t.call(`${user}: upload the batch (text/csv)`, "POST", B, { headers: h, raw: csv, contentType: "text/csv" }, [201, 202]);
        const b = (r.body ?? {}) as { batchId?: string; id?: string; status?: string };
        return out(`batch ${b.batchId ?? b.id ?? "?"}: ${b.status ?? r.status}`, { batchId: b.batchId ?? b.id });
      }
      case "list":
        await t.call(`${user}: list the org's batches`, "GET", B + (str(form, "status") ? `?status=${encodeURIComponent(str(form, "status"))}` : ""), { headers: h });
        return out("batches listed");
      case "get":
        needId();
        await t.call(`${user}: batch ${id}`, "GET", `${B}/${id}`, { headers: h });
        return out(`batch ${id}`);
      case "approve": case "pause": case "resume":
        needId();
        await t.call(`${user}: ${op} batch ${id}`, "POST", `${B}/${id}/${op}`, { headers: h, raw: "" }, [200, 202]);
        return out(`${op} sent (a maker approving their own batch is refused; use a checker of the same org)`);
      case "reject":
        needId();
        await t.call(`${user}: reject batch ${id}`, "POST", `${B}/${id}/reject`, { headers: h, json: { reason: str(form, "reason") || "rejected in the test console" } }, [200, 202]);
        return out("reject sent");
      case "result":
        needId();
        await t.call(`${user}: result file of batch ${id}`, "GET", `${B}/${id}/result`, { headers: h });
        return out("result file");
      case "txns":
        await t.call(`${user}: transactions${id ? ` of batch ${id}` : ""}`, "GET", `/portal/org/v1/transactions${id ? `?batchId=${encodeURIComponent(id)}` : ""}`, { headers: h });
        return out("transactions listed (an SP sees the ones it initiated or received)");
      default:
        return out("unknown step");
    }
  } catch (e) {
    t.note("stopped", (e as Error).message, false);
    return out((e as Error).message);
  }
}
