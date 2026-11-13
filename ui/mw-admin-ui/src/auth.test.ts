// Sign-out must hold even when a request that was in flight during it (a Link prefetch, which the
// proxy answers with a re-issued session cookie) lands after the sign-out response and puts the
// old cookie back: the session id is remembered as signed out and the jwt callback drops it.
import { beforeEach, describe, expect, it } from "vitest";
import type { JWT } from "next-auth/jwt";
import { authConfig } from "./auth";
import { clearSignedOut } from "./lib/signed-out";
import type { AuthEnv } from "./lib/server/env";

const env = {
  issuer: "https://localhost/realms/ops",
  clientId: "mw-admin-ui",
  secret: "test-secret-not-real",
  authBasePath: "/console/api/auth",
} as unknown as AuthEnv;

function accessToken(): string {
  const b64 = (o: object) => Buffer.from(JSON.stringify(o)).toString("base64url");
  return `${b64({ alg: "RS256" })}.${b64({ realm_access: { roles: ["viewer"] } })}.sig`;
}

async function login(cfg = authConfig(env)): Promise<JWT> {
  const jwt = cfg.callbacks!.jwt!;
  const token = await jwt({
    token: { sub: "u-1", name: "seed-ops-viewer" },
    account: {
      provider: "keycloak", type: "oidc", providerAccountId: "u-1",
      access_token: accessToken(), expires_at: Math.floor(Date.now() / 1000) + 300,
    },
    profile: { preferred_username: "seed-ops-viewer" },
  } as never);
  return token as JWT;
}

describe("auth sign-out", () => {
  beforeEach(() => clearSignedOut());

  it("gives every login its own session id", async () => {
    const a = await login();
    const b = await login();
    expect(a.sessionId).toBeTruthy();
    expect(b.sessionId).toBeTruthy();
    expect(a.sessionId).not.toBe(b.sessionId);
  });

  it("keeps a live session", async () => {
    const cfg = authConfig(env);
    const token = await login(cfg);
    expect(await cfg.callbacks!.jwt!({ token } as never)).toEqual(token);
  });

  it("drops the session cookie of a signed-out session, also when it comes back later", async () => {
    const cfg = authConfig(env);
    const token = await login(cfg);
    await cfg.events!.signOut!({ token } as never);
    expect(await cfg.callbacks!.jwt!({ token } as never)).toBeNull();
    expect(await cfg.callbacks!.jwt!({ token: { ...token } } as never)).toBeNull();
  });

  it("leaves other sessions of the same operator alone", async () => {
    const cfg = authConfig(env);
    const signedOut = await login(cfg);
    const other = await login(cfg);
    await cfg.events!.signOut!({ token: signedOut } as never);
    expect(await cfg.callbacks!.jwt!({ token: other } as never)).toEqual(other);
  });
});
