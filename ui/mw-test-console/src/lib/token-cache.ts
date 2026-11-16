// The Keycloak token cache rules of lib/server/tokens.ts and lib/server/ops-token.ts (as
// tests/api/lib.js token()): use a token until 30 s before it expires, then refresh, then log in
// again; Keycloak refuses a TOTP code the user already used, so never two logins of one user in
// one 30 s step. Pure, no I/O.

export type CachedToken = { access: string; refresh?: string; exp: number; rexp: number };
export type TokenResponse = { access_token: string; refresh_token?: string; expires_in: number; refresh_expires_in?: number };

const SLACK_MS = 30_000;
const STEP_MS = 30_000;

export function fromTokenResponse(b: TokenResponse, now: number): CachedToken {
  return { access: b.access_token, refresh: b.refresh_token, exp: now + b.expires_in * 1000, rexp: now + (b.refresh_expires_in ?? 0) * 1000 };
}

export function cacheDecision(t: CachedToken | undefined, now: number): "use" | "refresh" | "login" {
  if (!t) return "login";
  if (t.exp - SLACK_MS > now) return "use";
  if (t.refresh && t.rexp - SLACK_MS > now) return "refresh";
  return "login";
}

export const totpStep = (now: number): number => Math.floor(now / STEP_MS);

/** How long to wait before the next login of a user whose last used step is lastStep. */
export function totpWaitMs(lastStep: number | undefined, now: number): number {
  return lastStep === totpStep(now) ? STEP_MS - (now % STEP_MS) + 200 : 0;
}
