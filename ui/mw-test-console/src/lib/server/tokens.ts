import "server-only";
// Keycloak realm portals tokens through APISIX (password grant of the dev client mw-portal plus the
// TOTP code of the users' pre-set secret: the realm demands TOTP since ticket 73), cached per user in
// this process and refreshed with the refresh token. App users log in at mw-auth since ticket 69
// (lib/server/device.ts); their wallets are the personas' MSISDNs.

import { totp } from "@/lib/crypto";

import { appUsers, msisdnOf, type AppUser } from "@/lib/app-users";
import { APP_PERSONAS } from "@/lib/personas";
import { portalsPassword, portalsTotpSecret } from "./env";
import { EdgeError, edge, sleep } from "./edge";
import { loadManifest } from "./seed";

export type Realm = "portals";

const CLIENT: Record<Realm, string> = { portals: "mw-portal" };

type Cached = { access: string; refresh?: string; exp: number; rexp: number };
const g = globalThis as unknown as { __tcTokens?: Map<string, Cached>; __tcTotpSteps?: Map<string, number> };
const cache = (g.__tcTokens ??= new Map());
// Keycloak refuses a code a user has already used: the last 30 s step used per user
const totpSteps = (g.__tcTotpSteps ??= new Map());

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
    if (totpSteps.get(user) === Math.floor(Date.now() / 30000)) await sleep(30000 - (Date.now() % 30000) + 200);
    totpSteps.set(user, Math.floor(Date.now() / 30000));
    const r = await edge("POST", path, {
      form: { client_id: CLIENT[realm], grant_type: "password", username: user, password: portalsPassword(), totp: totp(portalsTotpSecret()) },
    });
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

/** The PIN-ready app users (lib/app-users.ts over mw-seeder's manifest; the personas only without one). */
export async function knownAppUsers(): Promise<AppUser[]> {
  const { manifest } = await loadManifest();
  return appUsers(manifest);
}

/** The wallet (MSISDN) an app user logs in with at mw-auth; an MSISDN typed in is its own wallet. */
export async function walletOf(user: string): Promise<string> {
  const p = APP_PERSONAS.find((x) => x.user === user);
  if (p) return p.msisdn;
  const w = msisdnOf(await knownAppUsers(), user);
  if (w) return w;
  throw new EdgeError(`no wallet known for app user ${user} (pick a seeded wallet or type an MSISDN)`);
}

export function forgetTokens(): void {
  cache.clear();
}
