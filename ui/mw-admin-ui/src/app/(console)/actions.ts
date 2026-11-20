"use server";
// Maker-checker writes (tickets 41, 52) as server actions: the operator's token stays on this
// server, mw-admin-api decides (roles, maker != checker, status rules) and audits. Inputs are
// checked here as the contract does, so a malformed form never reaches the API. On success the
// operator goes to the approvals inbox; a refusal comes back as the API's envelope.

import { redirect } from "next/navigation";
import type { CredentialUploadBody, ErrorMapBody, KeyChangeBody, PartnerChangeBody } from "@/lib/admin-api";
import { CEREMONY_PURPOSE, parseCheckValue, parseComponent, parseKeyId, parsePurpose } from "@/lib/keys";
import { parseReason, parseTimeouts, parseWalletId } from "@/lib/approvals";
import { parseRuleForm } from "@/lib/notify-rules";
import { parseCredentialUpload, parseEndpoint, parseErrorMap, parseMaintenance } from "@/lib/partner-tabs";
import { parseRouteForm } from "@/lib/partner-route";
import { adminApi, operatorToken } from "@/lib/server/admin";
import { parseTemplateKey, templateProblems, type TemplateKey, type TemplateTexts } from "@/lib/templates";
import type { ApiResult, ChangeRequest, ErrorEnvelope, PartnerAction, TxnAction, WalletAction } from "@/lib/types";
import { MNOS, parseActivations, parseMenu, parseMenuId, serverVerdict, type Lang, type ServerVerdict } from "@/lib/ussd-menu";

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
  return requestWallet(form, "UNLOCK");
}

/** Maker: a care PIN reset of a wallet (ticket 70; mw-auth opens it once a checker approves). */
export async function requestPinReset(_prev: ActionState, form: FormData): Promise<ActionState> {
  return requestWallet(form, "PIN_RESET");
}

async function requestWallet(form: FormData, action: WalletAction): Promise<ActionState> {
  const walletId = parseWalletId(String(form.get("walletId") ?? ""));
  const reason = parseReason(form.get("reason"));
  if (!walletId) return invalid("walletId", "an MSISDN 01[3-9]XXXXXXXX");
  if (!reason) return invalid("reason", "1 to 500 characters");
  const r = await adminApi().requestWalletAction(await token(), walletId, action, reason);
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

/**
 * Maker (ops-maker, integration-maker): a partner route as config (UPSERT_ROUTE, ADR D28, ticket
 * 83). mw-admin-api checks the route with the adapters' loader rules (MW-VAL-041, line and
 * construct per problem); once approved, it goes live in its adapter only after its dry-run.
 */
export async function requestPartnerRoute(partnerId: string, _prev: ActionState, form: FormData): Promise<ActionState> {
  const reason = parseReason(form.get("reason"));
  if (!PARTNER_ID.test(partnerId)) return invalid("partnerId", "not a partner id");
  const parsed = parseRouteForm({
    route: text(form, "route"), allowedHosts: text(form, "allowedHosts"), mappings: text(form, "mappings"),
    dryRunOperation: text(form, "dryRunOperation"), dryRunSample: text(form, "dryRunSample"), errorMap: text(form, "errorMap"),
    timeoutMs: text(form, "timeoutMs"),
  });
  if (!parsed.ok) return invalid(parsed.field, parsed.issue);
  if (!reason) return invalid("reason", "1 to 500 characters");
  const family = text(form, "family");
  const body: PartnerChangeBody = { action: "UPSERT_ROUTE", reason, config: parsed.config };
  if (family) {
    if (!["telco", "billpay", "bank"].includes(family)) return invalid("family", "telco, billpay or bank");
    body.family = family as PartnerChangeBody["family"];
  }
  const r = await adminApi().requestPartnerChange(await token(), partnerId, body);
  if (!r.ok) return refused(r);
  redirect(`/approvals?created=${encodeURIComponent(r.data.id)}`);
}

function raw(form: FormData, name: string): string | undefined {
  const v = form.get(name);
  return typeof v === "string" && v !== "" ? v : undefined;
}

/** Maker (ops-maker, integration-maker): the maintenance flag and window (ticket 131c). */
export async function requestPartnerMaintenance(partnerId: string, _prev: ActionState, form: FormData): Promise<ActionState> {
  const reason = parseReason(form.get("reason"));
  if (!PARTNER_ID.test(partnerId)) return invalid("partnerId", "not a partner id");
  const parsed = parseMaintenance({
    maintenance: text(form, "maintenance"), start: text(form, "start"), end: text(form, "end"),
    messageEn: text(form, "messageEn"), messageBn: text(form, "messageBn"),
  });
  if (!parsed.ok) return invalid(parsed.field, parsed.issue);
  if (!reason) return invalid("reason", "1 to 500 characters");
  const body: PartnerChangeBody = {
    action: "SET_MAINTENANCE", reason,
    config: {
      maintenance: parsed.value.maintenance,
      ...(parsed.value.maintenanceWindow ? { maintenanceWindow: parsed.value.maintenanceWindow } : {}),
    },
  };
  const r = await adminApi().requestPartnerChange(await token(), partnerId, body);
  if (!r.ok) return refused(r);
  redirect(`/approvals?created=${encodeURIComponent(r.data.id)}`);
}

/** Maker (ops-maker, integration-maker): MOCK or REAL and both endpoint URLs (ticket 131c). */
export async function requestPartnerEndpoint(partnerId: string, _prev: ActionState, form: FormData): Promise<ActionState> {
  const reason = parseReason(form.get("reason"));
  if (!PARTNER_ID.test(partnerId)) return invalid("partnerId", "not a partner id");
  const parsed = parseEndpoint({
    endpoint: text(form, "endpoint"), mockUrl: text(form, "mockUrl"), realUrl: text(form, "realUrl"),
    allowedHosts: text(form, "allowedHosts"),
  });
  if (!parsed.ok) return invalid(parsed.field, parsed.issue);
  if (!reason) return invalid("reason", "1 to 500 characters");
  const body: PartnerChangeBody = {
    action: "SET_ENDPOINT", reason,
    config: { endpoint: parsed.value.endpoint, endpoints: parsed.value.endpoints },
  };
  const r = await adminApi().requestPartnerChange(await token(), partnerId, body);
  if (!r.ok) return refused(r);
  redirect(`/approvals?created=${encodeURIComponent(r.data.id)}`);
}

/** Maker (ops-maker, integration-maker): replace the partner-code map (ticket 131c). */
export async function requestPartnerErrorMap(partnerId: string, _prev: ActionState, form: FormData): Promise<ActionState> {
  const reason = parseReason(form.get("reason"));
  if (!PARTNER_ID.test(partnerId)) return invalid("partnerId", "not a partner id");
  const parsed = parseErrorMap({ errorMap: text(form, "errorMap"), ambiguousCodes: text(form, "ambiguousCodes") });
  if (!parsed.ok) return invalid(parsed.field, parsed.issue);
  if (!reason) return invalid("reason", "1 to 500 characters");
  const body: ErrorMapBody = {
    action: "SET_ERROR_MAP", reason, errorMap: parsed.value.errorMap, ambiguousCodes: parsed.value.ambiguousCodes,
  };
  const r = await adminApi().requestErrorMapChange(await token(), partnerId, body);
  if (!r.ok) return refused(r);
  redirect(`/approvals?created=${encodeURIComponent(r.data.id)}`);
}

/**
 * Maker (integration-maker): upload a credential. The secret is posted and then dropped: a refusal
 * from this check never contains it, and the redirect carries only the change-request id (ticket 131c).
 */
export async function requestPartnerCredential(partnerId: string, _prev: ActionState, form: FormData): Promise<ActionState> {
  const reason = parseReason(form.get("reason"));
  if (!PARTNER_ID.test(partnerId)) return invalid("partnerId", "not a partner id");
  const parsed = parseCredentialUpload({
    name: text(form, "name"), type: text(form, "type"), secret: raw(form, "secret"),
    certificatePem: raw(form, "certificatePem"), privateKeyPem: raw(form, "privateKeyPem"),
    pkcs12: raw(form, "pkcs12"), pkcs12Password: raw(form, "pkcs12Password"), notAfter: text(form, "notAfter"),
  });
  if (!parsed.ok) return invalid(parsed.field, parsed.issue);
  if (!reason) return invalid("reason", "1 to 500 characters");
  const { name, ...fields } = parsed.value;
  const body: CredentialUploadBody = { action: "UPLOAD", reason, ...fields };
  const r = await adminApi().uploadCredential(await token(), partnerId, name, body);
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

/**
 * ops-maker / ops-checker: mw-admin-api's verdict on the edited tree (ticket 133b): its MenuValidator
 * and the dial screen on the MNO, with the short code and the root menu it opens. Nothing is stored.
 */
export async function previewMenuOnServer(menuId: string, mno: string, lang: Lang, menuJson: string): Promise<ServerVerdict> {
  const menu = parseMenu(menuJson);
  if (!parseMenuId(menuId) || !menu || !(MNOS as readonly string[]).includes(mno)) {
    return { ok: false, problems: ["the tree, the menu id or the MNO is not usable"] };
  }
  const r = await adminApi().previewMenu(await token(), { menu, menuId, mno, language: lang === "en" ? "en" : "bn" });
  return serverVerdict(r);
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

/** Maker: a new version of a notification rule (ticket 133c); mw-notify applies it once approved. */
export async function proposeNotifyRule(_prev: ActionState, form: FormData): Promise<ActionState> {
  const parsed = parseRuleForm({
    ruleId: text(form, "ruleId"), eventType: text(form, "eventType"), outcome: text(form, "outcome"),
    customerType: text(form, "customerType"), recipient: text(form, "recipient"),
    channels: form.getAll("channels").filter((c): c is string => typeof c === "string"),
    templateCode: text(form, "templateCode"), enabled: text(form, "enabled"), reason: text(form, "reason"),
  });
  if (!parsed.ok) return invalid(parsed.field, parsed.issue);
  const r = await adminApi().proposeNotifyRule(await token(), parsed.value.ruleId, parsed.value.body);
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

// --- keys and the key ceremony (ticket 75) ---

const KEY_FORM_ACTIONS = ["KEY_GENERATE", "KEY_ROTATE", "KEY_RETIRE"] as const;

/** Maker (security-admin, fresh MFA): generate, rotate or retire a key version. */
export async function requestKeyChange(_prev: ActionState, form: FormData): Promise<ActionState> {
  const action = form.get("action");
  const keyId = parseKeyId(form.get("keyId"));
  const reason = parseReason(form.get("reason"));
  if (typeof action !== "string" || !(KEY_FORM_ACTIONS as readonly string[]).includes(action)) return invalid("action", "not allowed");
  if (!keyId) return invalid("keyId", "lower-case letters, digits and hyphens");
  if (!reason) return invalid("reason", "1 to 500 characters");
  const body: KeyChangeBody = { action: action as KeyChangeBody["action"], keyId, reason };
  if (action === "KEY_GENERATE") {
    const purpose = parsePurpose(form.get("purpose"));
    if (!purpose) return invalid("purpose", "a key purpose, e.g. HMAC_WEBHOOK");
    body.purpose = purpose;
  }
  if (action === "KEY_RETIRE") {
    const v = Number(form.get("version"));
    if (!Number.isInteger(v) || v < 1) return invalid("version", "a VERIFY_ONLY version");
    body.version = v;
  }
  const r = await adminApi().requestKeyChange(await token(), body);
  if (!r.ok) return refused(r);
  redirect(`/approvals?created=${encodeURIComponent(r.data.id)}`);
}

/** Maker (security-admin): start a key ceremony; the custodians then enter their components. */
export async function startKeyCeremony(_prev: ActionState, form: FormData): Promise<ActionState> {
  const keyId = parseKeyId(form.get("keyId"));
  const purpose = parsePurpose(form.get("purpose"));
  const reason = parseReason(form.get("reason"));
  if (!keyId) return invalid("keyId", "lower-case letters, digits and hyphens");
  if (!purpose || !CEREMONY_PURPOSE.test(purpose)) return invalid("purpose", "PIN_HMAC, TOKENISE_<domain> or HMAC_<use>");
  if (!reason) return invalid("reason", "1 to 500 characters");
  const r = await adminApi().startKeyCeremony(await token(), { keyId, purpose, reason });
  if (!r.ok) return refused(r);
  redirect(`/keys/ceremony/${encodeURIComponent(r.data.id)}`);
}

/**
 * Custodian (security-admin, own session, fresh MFA): one component and its check value. The
 * component passes through this server to mw-admin-api and mw-crypto only: never logged or kept.
 */
export async function enterKeyComponent(id: string, _prev: ActionState, form: FormData): Promise<ActionState> {
  if (!UUID.test(id)) return invalid("id", "not a ceremony id");
  const component = parseComponent(form.get("component"));
  const checkValue = parseCheckValue(form.get("checkValue"));
  if (!component) return invalid("component", "64 hex digits");
  if (!checkValue) return invalid("checkValue", "6 hex digits");
  const r = await adminApi().enterKeyComponent(await token(), id, component, checkValue);
  if (!r.ok) return { status: r.status, error: r.error };
  redirect(`/keys/ceremony/${encodeURIComponent(id)}?entered=${r.data.shares.length}`);
}
