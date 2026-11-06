// What the transaction detail page loads: the detail, then (ticket 41) the check for an open request
// of the txn, turned into the actions the operator is offered (`txnActionGate`).
import type { createAdminApi } from "./admin-api";
import { txnActionGate } from "./approvals";
import type { ApiResult, TxnDetail } from "./types";

export type TxnGate = ReturnType<typeof txnActionGate>;

export async function loadTxnView(
  api: ReturnType<typeof createAdminApi>,
  token: string,
  txnId: string,
  roles: readonly string[],
  reveal: boolean,
): Promise<Extract<ApiResult<TxnDetail>, { ok: false }> | { ok: true; data: TxnDetail; gate: TxnGate }> {
  const result = await api.getTransaction(token, txnId, reveal);
  if (!result.ok) return result;
  // mw-admin-api allows one open request per txn; actions only once the check succeeded and found none
  const pendingQuery = new URLSearchParams({ resourceId: result.data.transaction.txnId, status: "PENDING", limit: "1" });
  const pending = await api.listChangeRequests(token, pendingQuery);
  return { ok: true, data: result.data, gate: txnActionGate(roles, result.data.transaction, pending) };
}
