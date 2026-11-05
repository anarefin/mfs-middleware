"use server";
// Maker-checker writes (tickets 41, 52) as server actions: the operator's token stays on this
// server, mw-admin-api decides (roles, maker != checker, status rules) and audits. Inputs are
// checked here as the contract does, so a malformed form never reaches the API. On success the
// operator goes to the approvals inbox; a refusal comes back as the API's envelope.

import { redirect } from "next/navigation";
import { parseReason, parseWalletId } from "@/lib/approvals";
import { adminApi, operatorToken } from "@/lib/server/admin";
import type { ApiResult, ChangeRequest, ErrorEnvelope, TxnAction } from "@/lib/types";

export type ActionState = { status: number; error: ErrorEnvelope } | null;

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const TXN_ACTIONS: readonly TxnAction[] = ["INQUIRE", "FORCE_COMMIT", "FORCE_RELEASE", "REVERSE"];

function invalid(field: string, issue: string): ActionState {
  return {
    status: 400,
    error: {
      code: "MW-VAL-001", message: "Invalid request", correlationId: crypto.randomUUID(), retryable: false,
      details: [{ field, issue }],
    },
  };
}

async function token(): Promise<string> {
  const t = await operatorToken();
  if (!t) redirect("/login?error=SessionExpired");
  return t;
}

function refused(r: Extract<ApiResult<ChangeRequest>, { ok: false }>): ActionState {
  return { status: r.status, error: r.error };
}

/** Maker: an action on a transaction (reversal, manual TSQ, force commit / release). */
export async function requestTxnAction(txnId: string, _prev: ActionState, form: FormData): Promise<ActionState> {
  const action = form.get("action");
  const reason = parseReason(form.get("reason"));
  if (!UUID.test(txnId)) return invalid("txnId", "not a transaction id");
  if (typeof action !== "string" || !(TXN_ACTIONS as readonly string[]).includes(action)) return invalid("action", "not allowed");
  if (!reason) return invalid("reason", "1 to 500 characters");
  const r = await adminApi().requestTxnAction(await token(), txnId, action as TxnAction, reason);
  if (!r.ok) return refused(r);
  redirect(`/approvals?created=${encodeURIComponent(r.data.id)}`);
}

/** Maker: unlock a PIN-locked wallet (ticket 52). */
export async function requestWalletUnlock(_prev: ActionState, form: FormData): Promise<ActionState> {
  const walletId = parseWalletId(String(form.get("walletId") ?? ""));
  const reason = parseReason(form.get("reason"));
  if (!walletId) return invalid("walletId", "an MSISDN 01[3-9]XXXXXXXX");
  if (!reason) return invalid("reason", "1 to 500 characters");
  const r = await adminApi().requestWalletAction(await token(), walletId, "UNLOCK", reason);
  if (!r.ok) return refused(r);
  redirect(`/approvals?created=${encodeURIComponent(r.data.id)}`);
}

/** Checker: approve (the API applies it in the same transaction). */
export async function approveRequest(id: string, _prev: ActionState, _form: FormData): Promise<ActionState> {
  if (!UUID.test(id)) return invalid("id", "not a change request id");
  const r = await adminApi().approveChangeRequest(await token(), id);
  if (!r.ok) return refused(r);
  redirect(`/approvals?status=all&decided=${encodeURIComponent(id)}`);
}

/** Checker: reject with a reason; nothing is applied. */
export async function rejectRequest(id: string, _prev: ActionState, form: FormData): Promise<ActionState> {
  const reason = parseReason(form.get("reason"));
  if (!UUID.test(id)) return invalid("id", "not a change request id");
  if (!reason) return invalid("reason", "1 to 500 characters");
  const r = await adminApi().rejectChangeRequest(await token(), id, reason);
  if (!r.ok) return refused(r);
  redirect(`/approvals?status=all&decided=${encodeURIComponent(id)}`);
}
