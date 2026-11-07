import "server-only";
// Keycloak tokens through APISIX (password grant of the dev clients mw-app and mw-portal), cached
// per user in this process and refreshed with the refresh token.

import { customersPassword, portalsPassword } from "./env";
import { EdgeError, edge, sleep } from "./edge";

export type Realm = "customers" | "portals";

const CLIENT: Record<Realm, string> = { customers: "mw-app", portals: "mw-portal" };
const password = (realm: Realm) => (realm === "customers" ? customersPassword() : portalsPassword());

type Cached = { access: string; refresh?: string; exp: number; rexp: number };
const g = globalThis as unknown as { __tcTokens?: Map<string, Cached> };
const cache = (g.__tcTokens ??= new Map());

function store(key: string, b: { access_token: string; refresh_token?: string; expires_in: number; refresh_expires_in?: number }): string {
  const now = Date.now();
  cache.set(key, { access: b.access_token, refresh: b.refresh_token, exp: now + b.expires_in * 1000, rexp: now + (b.refresh_expires_in ?? 0) * 1000 });
  return b.access_token;
}

export async function token(realm: Realm, user: string): Promise<string> {
  const key = `${realm}/${user}`;
  const path = `/auth/realms/${realm}/protocol/openid-connect/token`;
  const now = Date.now();
  const t = cache.get(key);
  if (t) {
    if (t.exp - 30000 > now) return t.access;
    if (t.refresh && t.rexp - 30000 > now) {
      const r = await edge("POST", path, { form: { client_id: CLIENT[realm], grant_type: "refresh_token", refresh_token: t.refresh } });
      if (r.status === 200) return store(key, r.body as never);
    }
  }
  let last = "";
  for (let i = 0; i < 3; i++) {
    const r = await edge("POST", path, { form: { client_id: CLIENT[realm], grant_type: "password", username: user, password: password(realm) } });
    if (r.status === 200) return store(key, r.body as never);
    last = `HTTP ${r.status} ${r.raw.slice(0, 300)}`;
    if (![400, 401, 429].includes(r.status)) break;
    await sleep(r.status === 429 ? 7000 : 1000);
  }
  throw new EdgeError(`no ${realm} token for ${user}: ${last}`);
}

export const bearer = async (realm: Realm, user: string) => ({ Authorization: `Bearer ${await token(realm, user)}` });

/** The claims of a JWT (not verified: the edge verifies; this only reads the msisdn / roles). */
export function claims(jwt: string): Record<string, unknown> {
  const part = jwt.split(".")[1] ?? "";
  return JSON.parse(Buffer.from(part, "base64url").toString("utf8")) as Record<string, unknown>;
}

export async function walletOf(user: string): Promise<string> {
  const m = claims(await token("customers", user)).msisdn;
  if (typeof m !== "string") throw new EdgeError(`the token of ${user} carries no msisdn claim`);
  return m;
}

export function forgetTokens(): void {
  cache.clear();
}
