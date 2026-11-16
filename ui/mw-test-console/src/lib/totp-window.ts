// Where "now" sits in the 30 s TOTP step of the realms ops and portals (RFC 6238, the period of
// lib/crypto.ts totp()): the live code on the logins pages refreshes at the boundary. Pure.

export const TOTP_STEP_MS = 30000;

export type TotpWindow = { step: number; remainingMs: number; remainingS: number; nextAtMs: number };

export function totpWindow(nowMs: number = Date.now()): TotpWindow {
  const step = Math.floor(nowMs / TOTP_STEP_MS);
  const nextAtMs = (step + 1) * TOTP_STEP_MS;
  const remainingMs = nextAtMs - nowMs;
  return { step, remainingMs, remainingS: Math.ceil(remainingMs / 1000), nextAtMs };
}

/** What the live-code server action answers. */
export type TotpNow = { ok: true; code: string; step: number; remainingMs: number } | { ok: false; error: string };
