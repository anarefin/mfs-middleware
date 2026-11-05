// Maker-checker rules as the console shows them (spec 15.3, tickets 37, 41, 52). mw-admin-api
// decides and enforces every one of them; these only choose which buttons an operator sees.

import type { ChangeRequest, Txn, TxnAction } from "./types";

export const MAKER_ROLE = "ops-maker";
export const CHECKER_ROLE = "ops-checker";

/** Button labels of the actions (INQUIRE is the manual TSQ). */
export const ACTION_LABELS: Record<string, string> = {
  REVERSE: "Request reversal",
  INQUIRE: "Request manual TSQ",
  FORCE_COMMIT: "Request force commit",
  FORCE_RELEASE: "Request force release",
  UNLOCK: "Request unlock",
};

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

export function canMake(roles: readonly string[]): boolean {
  return roles.includes(MAKER_ROLE);
}

/**
 * A checker decides a pending request somebody else filed. The maker never sees the buttons on
 * their own request, even when they also hold ops-checker (the API answers 403 MW-AUTH-030).
 * `makerId` is the maker's `preferred_username`, the session's `user.name`.
 */
export function mayDecide(cr: ChangeRequest, operator: string | null | undefined, roles: readonly string[]): boolean {
  return cr.status === "PENDING" && roles.includes(CHECKER_ROLE) && !!operator && cr.makerId !== operator;
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

export function diffLabel(cr: ChangeRequest): { before: string; after: string } {
  return { before: cr.diff.before?.status ?? "-", after: cr.diff.after?.status ?? "-" };
}
