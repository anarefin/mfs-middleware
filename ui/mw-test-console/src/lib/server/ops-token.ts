import "server-only";
// A realm ops token for mw-admin-api (the transaction explorer): password grant of the e2e client
// mw-e2e-ops with the pre-set TOTP secret, through APISIX's test-only route
// /auth/realms/ops/protocol/openid-connect/token (key-auth, 10/min per address; MW_E2E_ROUTES=1).
// Signs in as ops-e2e-maker01 (viewer + ops-maker: allowed to search and open transactions; no
// MSISDN reveal). Cached until 30 s before expiry, refreshed; one TOTP step per login (lib/token-cache).
// The values come from deploy/.env (KC_OPS_TEST_PASSWORD, KC_OPS_TEST_TOTP_SECRET): fixed fake dev values.

import { totp } from "@/lib/crypto";
import { cacheDecision, fromTokenResponse, totpStep, totpWaitMs, type CachedToken, type TokenResponse } from "@/lib/token-cache";
import { EdgeError, edge, sleep } from "./edge";

export const OPS_USER = "ops-e2e-maker01";
const CLIENT = "mw-e2e-ops";
const PATH = "/auth/realms/ops/protocol/openid-connect/token";

const g = globalThis as unknown as { __tcOpsToken?: CachedToken; __tcOpsTotpStep?: number };

/** The two ops values, or the message saying which is missing (never throws at import). */
export function opsCredentials(): { password: string; secret: string } | { missing: string } {
  const password = process.env.KC_OPS_TEST_PASSWORD;
  const secret = process.env.KC_OPS_TEST_TOTP_SECRET;
  if (password && secret) return { password, secret };
  const missing = [!password && "KC_OPS_TEST_PASSWORD", !secret && "KC_OPS_TEST_TOTP_SECRET"].filter(Boolean).join(", ");
  return { missing: `KC_OPS_TEST_* not set (${missing}): add them to the mw-test-console environment from deploy/.env` };
}

function keep(b: TokenResponse): string {
  g.__tcOpsToken = fromTokenResponse(b, Date.now());
  return b.access_token;
}

export async function opsToken(): Promise<string> {
  const creds = opsCredentials();
  if ("missing" in creds) throw new EdgeError(creds.missing);
  const decision = cacheDecision(g.__tcOpsToken, Date.now());
  if (decision === "use") return g.__tcOpsToken!.access;
  if (decision === "refresh") {
    const r = await edge("POST", PATH, { apikey: true, form: { client_id: CLIENT, grant_type: "refresh_token", refresh_token: g.__tcOpsToken!.refresh! } });
    if (r.status === 200) return keep(r.body as TokenResponse);
  }
  let last = "";
  for (let i = 0; i < 3; i++) {
    const wait = totpWaitMs(g.__tcOpsTotpStep, Date.now());
    if (wait) await sleep(wait);
    g.__tcOpsTotpStep = totpStep(Date.now());
    const r = await edge("POST", PATH, {
      apikey: true,
      form: { client_id: CLIENT, grant_type: "password", username: OPS_USER, password: creds.password, totp: totp(creds.secret) },
    });
    if (r.status === 200) return keep(r.body as TokenResponse);
    last = `HTTP ${r.status} ${r.raw.slice(0, 300)}`;
    if (![400, 401, 429].includes(r.status)) break;
    await sleep(r.status === 429 ? 7000 : 1000);
  }
  throw new EdgeError(`no ops token for ${OPS_USER}: ${last}${last.startsWith("HTTP 404") ? " (test routes off? scripts/tester.sh)" : ""}`);
}

export function forgetOpsToken(): void {
  g.__tcOpsToken = undefined;
}
