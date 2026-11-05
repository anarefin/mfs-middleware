"use server";
// Maker-checker writes (tickets 41, 52) as server actions: the operator's token stays on this
// server, mw-admin-api decides (roles, maker != checker, status rules) and audits. Inputs are
// checked here as the contract does, so a malformed form never reaches the API. On success the
// operator goes to the approvals inbox; a refusal comes back as the API's envelope.

import { redirect } from "next/navigation";
import type { PartnerChangeBody } from "@/lib/admin-api";
import { parseReason, parseTimeouts, parseWalletId } from "@/lib/approvals";
import { adminApi, operatorToken } from "@/lib/server/admin";
import { parseTemplateKey, templateProblems, type TemplateKey, type TemplateTexts } from "@/lib/templates";
import type { ApiResult, ChangeRequest, ErrorEnvelope, PartnerAction, TxnAction } from "@/lib/types";
import { parseActivations, parseMenu, parseMenuId } from "@/lib/ussd-menu";

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

const PARTNER_ID = /^[a-z0-9][a-z0-9-]{0,63}$/;
const PARTNER_FORM_ACTIONS = ["ENABLE", "DISABLE", "SET_TIMEOUTS"] as const;

function text(form: FormData, name: string): string | undefined {
  const v = form.get(name);
  return typeof v === "string" && v.trim() !== "" ? v : undefined;
}

function contentInvalid(problems: string[]): ActionState {
  return {
    status: 400,
    error: {
      code: "MW-VAL-040", message: "The proposed version is not usable", correlationId: crypto.randomUUID(),
      retryable: false, details: problems.map((p) => {
        const i = p.indexOf(": ");
        return i > 0 ? { field: p.slice(0, i), issue: p.slice(i + 2) } : { issue: p };
      }),
    },
  };
}

/** Maker (ops-maker, integration-maker): enable / disable a partner or change its timeouts (ticket 38). */
export async function requestPartnerChange(partnerId: string, _prev: ActionState, form: FormData): Promise<ActionState> {
  const action = form.get("action");
  const reason = parseReason(form.get("reason"));
  if (!PARTNER_ID.test(partnerId)) return invalid("partnerId", "not a partner id");
  if (typeof action !== "string" || !(PARTNER_FORM_ACTIONS as readonly string[]).includes(action)) return invalid("action", "not allowed");
  if (!reason) return invalid("reason", "1 to 500 characters");
  const body: PartnerChangeBody = { action: action as PartnerAction, reason };
  if (action === "SET_TIMEOUTS") {
    const t = parseTimeouts({
      connectTimeoutMs: text(form, "connectTimeoutMs"), timeoutMs: text(form, "timeoutMs"), readTimeoutMs: text(form, "readTimeoutMs"),
    });
    if (!t.ok) return invalid(t.field, t.issue);
    body.timeouts = t.timeouts;
  }
  const r = await adminApi().requestPartnerChange(await token(), partnerId, body);
  if (!r.ok) return refused(r);
  redirect(`/approvals?created=${encodeURIComponent(r.data.id)}`);
}

/** Maker (ops-maker): a new version of a USSD menu with its go-live per MNO (ticket 39). */
export async function proposeMenu(menuId: string, _prev: ActionState, form: FormData): Promise<ActionState> {
  const reason = parseReason(form.get("reason"));
  if (!parseMenuId(menuId)) return invalid("menuId", "not a menu id");
  const menu = parseMenu(String(form.get("menu") ?? ""));
  if (!menu) return invalid("menu", "a tree with start, messages and nodes");
  const mnos = form.getAll("mno").filter((m): m is string => typeof m === "string");
  const goLive = Object.fromEntries(mnos.map((m) => [m, String(form.get(`goLive.${m}`) ?? "")]));
  const activations = parseActivations(mnos, goLive);
  if (!activations) return invalid("activations", "pick each MNO once (gp, robi, bl, teletalk)");
  if (!reason) return invalid("reason", "1 to 500 characters");
  // the editor shows menuProblems as warnings; mw-admin-api's MenuValidator decides (MW-VAL-040)
  const r = await adminApi().proposeMenu(await token(), menuId, { menu, activations, reason });
  if (!r.ok) return refused(r);
  redirect(`/approvals?created=${encodeURIComponent(r.data.id)}`);
}

/** Maker (ops-maker): a new version of a notification template (ticket 39). */
export async function proposeTemplate(key: TemplateKey, _prev: ActionState, form: FormData): Promise<ActionState> {
  const k = parseTemplateKey(key.code, key.channel, key.locale);
  if (!k) return invalid("template", "code A-Z0-9_, channel SMS / EMAIL, locale bn / en");
  const reason = parseReason(form.get("reason"));
  const texts: TemplateTexts = {
    body: text(form, "body"), senderId: text(form, "senderId")?.trim(),
    ...(k.channel === "EMAIL" ? { subject: text(form, "subject"), htmlBody: text(form, "htmlBody") } : {}),
  };
  for (const f of ["subject", "htmlBody", "senderId"] as const) if (texts[f] === undefined) delete texts[f];
  const problems = templateProblems(k.channel, texts);
  if (problems.length > 0) return contentInvalid(problems);
  if (!reason) return invalid("reason", "1 to 500 characters");
  let validFrom: string | undefined;
  const vf = text(form, "validFrom");
  if (vf) {
    const d = new Date(/[zZ]|[+-]\d\d:\d\d$/.test(vf) ? vf : `${vf}Z`);
    if (Number.isNaN(d.getTime())) return invalid("validFrom", "a date and time (UTC)");
    validFrom = d.toISOString();
  }
  const r = await adminApi().proposeTemplate(await token(), k, {
    body: texts.body!, ...(texts.subject ? { subject: texts.subject } : {}), ...(texts.htmlBody ? { htmlBody: texts.htmlBody } : {}),
    ...(texts.senderId ? { senderId: texts.senderId } : {}), ...(validFrom ? { validFrom } : {}), reason,
  });
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
