// Maker-checker rules as the console shows them (spec 15.3, tickets 37, 38, 39, 41, 42, 52, 70).
// mw-admin-api decides and enforces every one of them; these only choose which buttons an
// operator sees.

import type { ChangeRequest, ChangeSide, PartnerTimeouts, Txn, TxnAction } from "./types";

export const MAKER_ROLE = "ops-maker";
export const CHECKER_ROLE = "ops-checker";
/** mw-admin-api AdminRole.PARTNER_MAKER / PARTNER_CHECKER (ticket 38). */
export const PARTNER_MAKER_ROLES = ["ops-maker", "integration-maker"] as const;
export const PARTNER_CHECKER_ROLES = ["ops-checker", "integration-checker"] as const;

/** Button labels of the actions (INQUIRE is the manual TSQ). */
export const ACTION_LABELS: Record<string, string> = {
  REVERSE: "Request reversal",
  INQUIRE: "Request manual TSQ",
  FORCE_COMMIT: "Request force commit",
  FORCE_RELEASE: "Request force release",
  UNLOCK: "Request unlock",
  PIN_RESET: "Request PIN reset",
  KEY_GENERATE: "Generate key",
  KEY_ROTATE: "Rotate key",
  KEY_RETIRE: "Retire key version",
  KEY_CEREMONY: "Key ceremony",
};

/** How the approvals inbox names a resource type (ticket 70: the care PIN reset). */
export const RESOURCE_LABELS: Record<string, string> = {
  transaction: "Transaction",
  wallet: "Wallet",
  pin_reset: "PIN reset",
  partner: "Partner",
  ussd_menu: "USSD menu",
  notify_template: "Template",
  key: "Key",
};

/** A request on one wallet (ticket 52 unlock, ticket 70 care PIN reset): status diff, masked id. */
export function walletRequest(cr: ChangeRequest): boolean {
  return cr.resourceType === "wallet" || cr.resourceType === "pin_reset";
}

/** The actions mw-admin-api accepts for a transaction in this state (409 MW-VAL-030 otherwise). */
export function txnActionsFor(t: Txn): TxnAction[] {
  switch (t.status) {
    case "UNKNOWN":
      return ["INQUIRE"];
    case "MANUAL_REVIEW":
      return ["INQUIRE", "FORCE_COMMIT", "FORCE_RELEASE"];
    case "COMPLETED":
      return t.reversalTxnId || t.txnType === "REVERSAL" ? [] : ["REVERSE"];
    default:
      return [];
  }
}

/** Files transaction, wallet, USSD menu and template requests (AdminRole TXN_MAKER, CONTENT_MAKER). */
export function canMake(roles: readonly string[]): boolean {
  return roles.includes(MAKER_ROLE);
}

/**
 * The transaction page's action buttons (ticket 41): only for an ops-maker, only those the status
 * allows, and only once the check for an open PENDING request (one per transaction) succeeded and
 * found none. A failed check offers no buttons and `checkFailed` (the page says it could not check).
 */
export function txnActionGate(
  roles: readonly string[],
  t: Txn,
  pending: { ok: true; data: { items: ChangeRequest[] } } | { ok: false },
): { actions: TxnAction[]; open: ChangeRequest | undefined; checkFailed: boolean } {
  const maker = canMake(roles);
  if (!pending.ok) return { actions: [], open: undefined, checkFailed: maker };
  const open = pending.data.items[0];
  return { actions: maker && !open ? txnActionsFor(t) : [], open, checkFailed: false };
}

/** Files partner changes: ops-maker or integration-maker (an incident disable is an operations task). */
export function canMakePartner(roles: readonly string[]): boolean {
  return PARTNER_MAKER_ROLES.some((r) => roles.includes(r));
}

/** mw-admin-api AdminRole.KEY_ADMIN (ticket 75): key requests are decided by another security-admin. */
export const KEY_CHECKER_ROLE = "security-admin";

function checkerRoles(cr: ChangeRequest): readonly string[] {
  if (cr.resourceType === "partner") return PARTNER_CHECKER_ROLES;
  if (cr.resourceType === "key") return [KEY_CHECKER_ROLE];
  return [CHECKER_ROLE];
}

/** Who decides this request, for the "waiting for ..." line. */
export function checkerLabel(cr: ChangeRequest): string {
  if (cr.resourceType === "partner") return "an ops-checker or integration-checker";
  if (cr.resourceType === "key") return "another security-admin";
  return "an ops-checker";
}

/**
 * A checker decides a pending request somebody else filed (ops-checker; for a partner also
 * integration-checker). The maker never sees the buttons on their own request, even when they
 * also hold the checker role (the API answers 403 MW-AUTH-030).
 * `makerId` is the maker's `preferred_username`, the session's `user.name`.
 */
export function mayDecide(cr: ChangeRequest, operator: string | null | undefined, roles: readonly string[]): boolean {
  return cr.status === "PENDING" && checkerRoles(cr).some((r) => roles.includes(r)) && !!operator && cr.makerId !== operator;
}

/**
 * A key request left APPROVING (ticket 75: mw-crypto did not answer the approval's call): another
 * security-admin than the maker may retry the approval (the same change request id is sent again);
 * nobody can reject it any more.
 */
export function mayRetryApproval(cr: ChangeRequest, operator: string | null | undefined, roles: readonly string[]): boolean {
  return cr.status === "APPROVING" && cr.resourceType === "key" && roles.includes(KEY_CHECKER_ROLE) && !!operator
    && cr.makerId !== operator;
}

const WALLET_ID = /^01[3-9]\d{8}$/;

/** A wallet id (its MSISDN) in any form (01..., 880..., +880 with spaces or dashes) -> 01XXXXXXXXX. */
export function parseWalletId(raw: string | null | undefined): string | null {
  const digits = (raw ?? "").replace(/[\s\-+]/g, "");
  if (!/^\d+$/.test(digits)) return null;
  const id = digits.startsWith("880") ? `0${digits.slice(3)}` : digits;
  return WALLET_ID.test(id) ? id : null;
}

/** The reason of a request or a rejection: 1..500 characters after trimming (contract). */
export function parseReason(raw: unknown): string | null {
  if (typeof raw !== "string") return null;
  const r = raw.trim();
  return r.length >= 1 && r.length <= 500 ? r : null;
}

function side(cr: ChangeRequest, s: ChangeSide | undefined): string {
  if (!s) return "-";
  if (cr.resourceType === "transaction" || walletRequest(cr)) return s.status ?? "-";
  if (cr.resourceType === "ussd_menu" || cr.resourceType === "notify_template") {
    return typeof s.version === "number" ? `v${s.version}` : "-";
  }
  if (cr.resourceType === "key") return typeof s.key === "string" ? s.key : "-";
  // partner: the changed top-level config fields (objects in short; never a secret, refs only)
  const parts = Object.keys(s).sort().map((k) => {
    const v = s[k];
    return `${k}=${typeof v === "object" && v !== null ? JSON.stringify(v) : String(v)}`;
  });
  return parts.join(", ") || "-";
}

/** The diff, one line per side: status, changed partner fields, or menu / template version. */
export function diffLabel(cr: ChangeRequest): { before: string; after: string } {
  return { before: side(cr, cr.diff.before), after: side(cr, cr.diff.after) };
}

export type TimeoutsResult = { ok: true; timeouts: PartnerTimeouts } | { ok: false; field: string; issue: string };

const LIMITS = { connectTimeoutMs: 120000, timeoutMs: 120000, readTimeoutMs: 125000 } as const;

/** SET_TIMEOUTS form fields (empty = unchanged) -> the contract's PartnerTimeouts. */
export function parseTimeouts(form: Partial<Record<keyof typeof LIMITS, string>>): TimeoutsResult {
  const timeouts: PartnerTimeouts = {};
  for (const k of Object.keys(LIMITS) as (keyof typeof LIMITS)[]) {
    const raw = form[k]?.trim();
    if (!raw) continue;
    const n = /^\d+$/.test(raw) ? Number(raw) : NaN;
    if (!(n >= 1 && n <= LIMITS[k])) return { ok: false, field: k, issue: `a whole number 1 to ${LIMITS[k]}` };
    timeouts[k] = n;
  }
  if (Object.keys(timeouts).length === 0) return { ok: false, field: "timeouts", issue: "at least one timeout" };
  if (timeouts.readTimeoutMs !== undefined && timeouts.timeoutMs !== undefined && timeouts.readTimeoutMs < timeouts.timeoutMs) {
    return { ok: false, field: "readTimeoutMs", issue: "at least timeoutMs" };
  }
  return { ok: true, timeouts };
}
