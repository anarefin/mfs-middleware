import "server-only";
// Keycloak realm portals tokens through APISIX (password grant of the dev client mw-portal), cached
// per user in this process and refreshed with the refresh token. App users log in at mw-auth since
// ticket 69 (lib/server/device.ts); their wallets are the personas' MSISDNs.

import { APP_PERSONAS } from "@/lib/personas";
import { portalsPassword } from "./env";
import { EdgeError, edge, sleep } from "./edge";

export type Realm = "portals";

const CLIENT: Record<Realm, string> = { portals: "mw-portal" };

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
    const r = await edge("POST", path, { form: { client_id: CLIENT[realm], grant_type: "password", username: user, password: portalsPassword() } });
    if (r.status === 200) return store(key, r.body as never);
    last = `HTTP ${r.status} ${r.raw.slice(0, 300)}`;
    if (![400, 401, 429].includes(r.status)) break;
    await sleep(r.status === 429 ? 7000 : 1000);
  }
  throw new EdgeError(`no ${realm} token for ${user}: ${last}`);
}

export const bearer = async (realm: Realm, user: string) => ({ Authorization: `Bearer ${await token(realm, user)}` });

/** The claims of a JWT (not verified: the edge verifies; this only reads them). */
export function claims(jwt: string): Record<string, unknown> {
  const part = jwt.split(".")[1] ?? "";
  return JSON.parse(Buffer.from(part, "base64url").toString("utf8")) as Record<string, unknown>;
}

/** mw-seeder's app users beyond the personas (every app wallet has the seeded app PIN). */
const SEEDED_APP_USERS: Record<string, string> = {
  "seed-customer01": "01712000001", "seed-customer02": "01712000002", "seed-agent01": "01812000001",
  "seed-merchant01": "01912000001",
};

/** The wallet (MSISDN) an app user logs in with at mw-auth; an MSISDN typed in is its own wallet. */
export function walletOf(user: string): string {
  const p = APP_PERSONAS.find((x) => x.user === user);
  if (p) return p.msisdn;
  if (SEEDED_APP_USERS[user]) return SEEDED_APP_USERS[user];
  if (/^01[3-9][0-9]{8}$/.test(user)) return user;
  throw new EdgeError(`no wallet known for app user ${user} (type a persona, a seed-* user or an MSISDN)`);
}

export function forgetTokens(): void {
  cache.clear();
}
