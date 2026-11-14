// Keys and the key ceremony as the console shows them (ticket 75, spec 18.8, OWN-08). mw-admin-api
// and mw-crypto decide and enforce every rule (security-admin maker and a different security-admin
// checker, fresh MFA, two different custodians, KCV only); these only choose what an operator sees.

import type { Key, KeyCeremony, KeyVersion } from "./types";

/** mw-admin-api AdminRole.KEY_ADMIN: files, enters components, decides. */
export const KEY_ADMIN_ROLE = "security-admin";
/** AdminRole.READ_KEYS: sees the keys page. */
export const KEY_READER_ROLES = ["security-admin", "auditor"] as const;

/** The purposes a ceremony makes (HMAC-SHA256 keys; KEKs and the payShield LMK are a follow-up). */
export const CEREMONY_PURPOSE = /^(PIN_HMAC|TOKENISE_[A-Z][A-Z0-9]{0,23}|HMAC_[A-Z][A-Z0-9]{0,23})$/;
const KEY_ID = /^[a-z0-9][a-z0-9-]{0,62}$/;
const PURPOSE = /^[A-Z][A-Z0-9_]{0,63}$/;

export function canAdminKeys(roles: readonly string[]): boolean {
  return roles.includes(KEY_ADMIN_ROLE);
}

export function canReadKeys(roles: readonly string[]): boolean {
  return KEY_READER_ROLES.some((r) => roles.includes(r));
}

export function parseKeyId(raw: unknown): string | null {
  const v = typeof raw === "string" ? raw.trim() : "";
  return KEY_ID.test(v) ? v : null;
}

export function parsePurpose(raw: unknown): string | null {
  const v = typeof raw === "string" ? raw.trim().toUpperCase() : "";
  return PURPOSE.test(v) ? v : null;
}

/**
 * A custodian's component as typed from the paper share: 64 hex digits, spaces and dashes between
 * groups allowed; upper-cased. Null when it is not 32 bytes of hex.
 */
export function parseComponent(raw: unknown): string | null {
  const v = typeof raw === "string" ? raw.replace(/[\s-]/g, "").toUpperCase() : "";
  return /^[0-9A-F]{64}$/.test(v) ? v : null;
}

/** A 6-digit hex check value, upper-cased. */
export function parseCheckValue(raw: unknown): string | null {
  const v = typeof raw === "string" ? raw.trim().toUpperCase() : "";
  return /^[0-9A-F]{6}$/.test(v) ? v : null;
}

export function activeVersion(k: Key): KeyVersion | undefined {
  return k.versions.find((v) => v.state === "ACTIVE");
}

/** The versions a retire request may name: VERIFY_ONLY ones (rotate first to retire the ACTIVE one). */
export function retirable(k: Key): KeyVersion[] {
  return k.versions.filter((v) => v.state === "VERIFY_ONLY");
}

export type CeremonyStep = "custodian1" | "custodian2" | "confirm" | "closed";

/** Where the wizard is: waiting for share 1, share 2, the checker's KCV confirmation, or decided. */
export function ceremonyStep(c: KeyCeremony): CeremonyStep {
  if (c.status !== "PENDING") return "closed";
  if (c.shares.length === 0) return "custodian1";
  if (c.shares.length === 1) return "custodian2";
  return "confirm";
}

/**
 * Whether this operator may enter the next component now: a security-admin, the ceremony waiting
 * for a share, and not the custodian of share 1 (two different custodians).
 */
export function mayEnterComponent(c: KeyCeremony, operator: string | null | undefined, roles: readonly string[]): boolean {
  const step = ceremonyStep(c);
  if (!canAdminKeys(roles) || !operator) return false;
  if (step === "custodian1") return true;
  if (step === "custodian2") return c.shares[0]?.custodian !== operator;
  return false;
}

/** Whether the KCV the checker expects (from the key's paper record) is the one the HSM answered. */
export function kcvMatches(c: KeyCeremony, expected: string | null | undefined): boolean {
  const e = parseCheckValue(expected);
  return !!e && !!c.checkValue && c.checkValue.toUpperCase() === e;
}
