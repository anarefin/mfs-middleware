"use server";
// SP portal (/portal/sp/v1, realm portals, role sp-viewer, ticket 129d): a service provider reads
// its own transactions and a CSV report of them. The org is the token's; no request carries one.

import { reportSummary, type SpOp, spPath } from "@/lib/sp-portal";
import type { Outcome } from "@/lib/types";
import { Trace } from "@/lib/server/edge";
import { bearer } from "@/lib/server/tokens";

export type SpState = Outcome | null;
const str = (f: FormData, k: string) => String(f.get(k) ?? "").trim();
const OPS: readonly SpOp[] = ["txns", "txn", "report"];

export async function sp(_prev: SpState, form: FormData): Promise<SpState> {
  const t = new Trace();
  const op = str(form, "op") as SpOp;
  const user = str(form, "user");
  const out = (headline: string, data?: Record<string, unknown>): Outcome => ({ ok: !t.failed, headline, steps: t.steps, data });
  if (!/^[a-z0-9-]{3,64}$/.test(user)) return out("choose a portal user");
  if (!OPS.includes(op)) return out("unknown step");
  try {
    const path = spPath(op, { status: str(form, "status"), from: str(form, "from"), to: str(form, "to"), txnId: str(form, "txnId") });
    const h = await bearer("portals", user);
    switch (op) {
      case "txns":
        await t.call(`${user}: the SP's transactions`, "GET", path, { headers: h });
        return out("transactions listed (role INITIATOR or PAYEE; never another org's)");
      case "txn":
        await t.call(`${user}: one transaction`, "GET", path, { headers: h });
        return out("transaction and its state changes");
      case "report": {
        const r = await t.call(`${user}: CSV report`, "GET", path, { headers: h });
        return out(reportSummary(r.status === 200 ? r.raw : undefined), { csv: r.status === 200 ? r.raw : "" });
      }
    }
  } catch (e) {
    t.note("stopped", (e as Error).message, false);
    return out((e as Error).message);
  }
}
