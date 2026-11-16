"use server";
// The transaction explorer: mw-admin-api's search and detail (GET /admin/v1/transactions[/{txnId}],
// contract admin.v1) with a realm ops token of ops-e2e-maker01 (lib/server/ops-token). The detail
// is the transaction, its saga timeline and partner calls (mw-orchestrator) and its audit trail
// (mw-audit-dw), MSISDNs masked as the API returns them.

import { isTxnId, parseTxnSearch, type AdminTxn, type TxnDetail, type TxnPage, type TxnSearch } from "@/lib/txn-query";
import type { Step } from "@/lib/types";
import { EdgeError, Trace, codeOf } from "@/lib/server/edge";
import { OPS_USER, opsToken } from "@/lib/server/ops-token";

export type ExplorerState = {
  ok: boolean;
  headline: string;
  steps: Step[];
  search?: TxnSearch;
  items?: AdminTxn[];
  detail?: TxnDetail;
};

const fields = (f: FormData): Record<string, unknown> => Object.fromEntries(f.entries());

async function call(t: Trace, label: string, path: string): Promise<{ status: number; body: unknown; error?: string }> {
  const token = await opsToken();
  const r = await t.call(label, "GET", path, { headers: { Authorization: `Bearer ${token}` } });
  const step = t.steps[t.steps.length - 1];
  // the page shows a good answer in full below; keep only failures in the step
  if (step && r.status === 200) step.body = undefined;
  const b = r.body as { message?: string } | null;
  return { status: r.status, body: r.body, error: r.status === 200 ? undefined : `HTTP ${r.status} ${codeOf(r) ?? ""} ${b?.message ?? r.raw.slice(0, 200)}`.trim() };
}

/** op=search (the form's filters) or op=detail (txnId); a search by txnId opens its detail too. */
export async function explore(prev: ExplorerState | null, form: FormData): Promise<ExplorerState> {
  const t = new Trace();
  const op = String(form.get("op") ?? "search");
  try {
    if (op === "detail") {
      const txnId = String(form.get("txnId") ?? "").trim().toLowerCase();
      if (!isTxnId(txnId)) return { ok: false, headline: "the txnId must be a UUID", steps: [], search: prev?.search, items: prev?.items };
      t.note(`signed in as ${OPS_USER}`, "realm ops, client mw-e2e-ops (viewer, ops-maker)");
      const d = await call(t, "the transaction, its timeline, partner calls and audit trail", `/admin/v1/transactions/${txnId}`);
      if (d.error) return { ok: false, headline: `no detail for ${txnId}: ${d.error}`, steps: t.steps, search: prev?.search, items: prev?.items };
      const detail = d.body as TxnDetail;
      return { ok: true, headline: `${detail.transaction.txnType} ${detail.transaction.status}`, steps: t.steps, search: prev?.search, items: prev?.items, detail };
    }
    const p = parseTxnSearch(fields(form));
    if (!p.ok) return { ok: false, headline: p.error, steps: [] };
    t.note(`signed in as ${OPS_USER}`, "realm ops, client mw-e2e-ops (viewer, ops-maker)");
    const s = await call(t, "search the transactions", `/admin/v1/transactions?${p.query}`);
    if (s.error) return { ok: false, headline: `search refused: ${s.error}`, steps: t.steps, search: p.search };
    const items = (s.body as TxnPage).items ?? [];
    const out: ExplorerState = {
      ok: true, steps: t.steps, search: p.search, items,
      headline: items.length ? `${items.length} transaction(s)${items.length === p.search.limit ? " (more on the next page)" : ""}` : "No transactions found",
    };
    if (p.search.txnId && items.length === 1) {
      const d = await call(t, "the transaction, its timeline, partner calls and audit trail", `/admin/v1/transactions/${p.search.txnId}`);
      if (d.error) return { ...out, ok: false, headline: `found, but no detail: ${d.error}`, steps: t.steps };
      out.detail = d.body as TxnDetail;
    }
    return out;
  } catch (e) {
    if (!(e instanceof EdgeError)) throw e;
    t.note("ops token", e.message, false);
    return { ok: false, headline: e.message, steps: t.steps, search: prev?.search, items: prev?.items };
  }
}
