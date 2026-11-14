// Pure helpers of the wallet authentication page (ticket 76): mw-auth's OTP SMS texts, the fresh
// wallets a tester can use without touching the seeded ones, the new-device (cooling) window and
// the details of an error envelope.

export type OtpPurpose = "BIND" | "LOGIN" | "RESET";

/** The SMS texts of mw-notify's templates DEVICE_BIND_OTP, LOGIN_OTP and PIN_RESET_OTP (en). */
export const OTP_TEXT: Record<OtpPurpose, RegExp> = {
  BIND: /Your Bits code to bind a new device is ([0-9]{6})/,
  LOGIN: /Your Bits code to sign in and set your PIN is ([0-9]{6})/,
  RESET: /Your Bits code to reset your PIN is ([0-9]{6})/,
};

/**
 * A candidate fresh wallet: one of core-mock's seeded customers 01711000500..01711000999 (mw-seeder
 * gives an app PIN only to its own wallets and customer01/02, agent01, merchant01/02). The page asks
 * mw-auth whether it has a PIN (a LOGIN OTP goes only to a wallet without one) before it uses it.
 */
export function freshCandidate(random: () => number = Math.random): string {
  const n = 500 + Math.min(499, Math.floor(random() * 500));
  return `017110${String(n).padStart(5, "0")}`;
}

/**
 * mw-auth marks every transaction assertion of a wallet `newDevice: true` while its bound device was
 * bound less than `mw.auth.assertion.new-device-window` (24 h) ago: the cooling-off flag the core can
 * act on. It travels only in the signed assertion to the core, so the page shows the window it implies.
 */
export const NEW_DEVICE_WINDOW_HOURS = 24;

export function coolingUntil(boundAt: string, hours: number = NEW_DEVICE_WINDOW_HOURS): string | null {
  const t = Date.parse(boundAt);
  return Number.isNaN(t) ? null : new Date(t + hours * 3600_000).toISOString();
}

export function inCooling(boundAt: string, now: number = Date.now(), hours: number = NEW_DEVICE_WINDOW_HOURS): boolean {
  const t = Date.parse(boundAt);
  return !Number.isNaN(t) && now >= t && now < t + hours * 3600_000;
}

/** The `issue` of an error envelope's detail `field` (triesLeft, retryAfter, encryptedPin = the weak rule). */
export function detailOf(body: unknown, field: string): string | undefined {
  const d = (body as { details?: { field?: string; issue?: string }[] } | null)?.details;
  return Array.isArray(d) ? d.find((x) => x.field === field)?.issue : undefined;
}

/** A 5-digit PIN (mw-crypto's PIN rule). */
export const PIN_RE = /^[0-9]{5}$/;
