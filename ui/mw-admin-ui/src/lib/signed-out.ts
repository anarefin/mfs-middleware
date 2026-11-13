// Sessions signed out on this console server (src/auth.ts). The session is a self-contained JWT
// cookie, and the proxy re-issues it on every request (rolling expiry): a request in flight while
// the operator signs out (a Link prefetch) can land after the sign-out response and set the old
// cookie again. The jwt callback refuses a session id kept here, so such a cookie is dropped on its
// next use. Kept in process memory (one console instance per stack); on globalThis because the proxy
// and the app are bundled separately and must see one set. A restart forgets it; the session then
// still ends when its access token can no longer be refreshed (Keycloak's session is ended too).

type Store = Map<string, number>; // session id -> epoch ms until which its cookie could be valid

const KEY = Symbol.for("mw-admin-ui.signed-out-sessions");

function store(): Store {
  const g = globalThis as unknown as Record<symbol, Store | undefined>;
  return (g[KEY] ??= new Map());
}

/** Remembers a session as signed out until `untilMs` (when its cookie would have expired anyway). */
export function markSignedOut(sessionId: string | undefined, untilMs: number, nowMs: number = Date.now()): void {
  if (!sessionId) return;
  const s = store();
  for (const [id, until] of s) if (until <= nowMs) s.delete(id);
  s.set(sessionId, untilMs);
}

export function isSignedOut(sessionId: string | undefined, nowMs: number = Date.now()): boolean {
  if (!sessionId) return false;
  const until = store().get(sessionId);
  return until !== undefined && nowMs < until;
}

/** Tests only. */
export function clearSignedOut(): void {
  store().clear();
}
