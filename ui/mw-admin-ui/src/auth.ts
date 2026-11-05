// Auth.js (next-auth v5) against Keycloak realm `ops` (spec 15.1): Authorization Code + PKCE,
// MFA enforced by the realm's browser flow (password + TOTP). The session is a JWT kept in an
// encrypted (JWE, AUTH_SECRET), HTTP-only cookie; the access and refresh tokens live only in that
// cookie and in this server's memory. The session object the browser can read (/api/auth/session)
// carries the operator's name and roles, never a token.

import NextAuth, { customFetch, type NextAuthConfig } from "next-auth";
import type { JWT } from "next-auth/jwt";
import Keycloak from "next-auth/providers/keycloak";
import { expiresSoon, internalRequest, rolesOf } from "@/lib/oidc";
import { authEnv, BASE_PATH, type AuthEnv } from "@/lib/server/env";

declare module "next-auth" {
  interface Session {
    roles: string[];
    error?: "RefreshFailed";
  }
}

declare module "next-auth/jwt" {
  interface JWT {
    accessToken?: string;
    refreshToken?: string;
    expiresAt?: number;
    roles?: string[];
    error?: "RefreshFailed";
  }
}

/** fetch for every server-side call to Keycloak: to the in-stack URL when one is configured. */
export function keycloakFetch(env: AuthEnv): typeof fetch {
  return (input, init) => {
    const url = input instanceof Request ? input.url : String(input);
    const internal = internalRequest(url, env.issuer, env.internalUrl);
    if (!internal) return fetch(input, init);
    const headers = new Headers(input instanceof Request ? input.headers : undefined);
    new Headers(init?.headers).forEach((v, k) => headers.set(k, v));
    for (const [k, v] of Object.entries(internal.headers)) headers.set(k, v);
    const body = init?.body ?? (input instanceof Request ? input.body : undefined);
    return fetch(internal.url, {
      ...init,
      method: init?.method ?? (input instanceof Request ? input.method : "GET"),
      headers,
      body,
      ...(body ? { duplex: "half" } : {}),
      redirect: "manual",
    } as RequestInit);
  };
}

type TokenAnswer = { access_token: string; refresh_token?: string; expires_in?: number };

/** A new access token from the refresh token; the old JWT with an error when Keycloak refuses. */
export async function refreshAccessToken(token: JWT, env: AuthEnv = authEnv()): Promise<JWT> {
  if (!token.refreshToken) return { ...token, accessToken: undefined, error: "RefreshFailed" };
  try {
    const body = new URLSearchParams({
      grant_type: "refresh_token", refresh_token: token.refreshToken, client_id: env.clientId,
    });
    if (env.clientSecret) body.set("client_secret", env.clientSecret);
    const res = await keycloakFetch(env)(`${env.issuer}/protocol/openid-connect/token`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded", accept: "application/json" },
      body: body.toString(),
      cache: "no-store",
    });
    if (!res.ok) throw new Error(`refresh refused: ${res.status}`);
    const t = (await res.json()) as TokenAnswer;
    return {
      ...token,
      accessToken: t.access_token,
      refreshToken: t.refresh_token ?? token.refreshToken,
      expiresAt: Math.floor(Date.now() / 1000) + (t.expires_in ?? 60),
      roles: rolesOf(t.access_token),
      error: undefined,
    };
  } catch {
    return { ...token, accessToken: undefined, error: "RefreshFailed" };
  }
}

/** Ends the Keycloak session too (back-channel, with the refresh token), not only ours. */
async function endKeycloakSession(token: JWT | null | undefined, env: AuthEnv): Promise<void> {
  if (!token?.refreshToken) return;
  const body = new URLSearchParams({ refresh_token: token.refreshToken, client_id: env.clientId });
  if (env.clientSecret) body.set("client_secret", env.clientSecret);
  try {
    await keycloakFetch(env)(`${env.issuer}/protocol/openid-connect/logout`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: body.toString(),
    });
  } catch {
    // the local session is gone either way; Keycloak's ends with its SSO idle timeout
  }
}

export function authConfig(env: AuthEnv): NextAuthConfig {
  return {
    trustHost: true,
    secret: env.secret,
    basePath: env.authBasePath,
    session: { strategy: "jwt", maxAge: 8 * 60 * 60 },
    pages: { signIn: `${BASE_PATH}/login`, error: `${BASE_PATH}/login` },
    providers: [
      Keycloak({
        clientId: env.clientId,
        clientSecret: env.clientSecret,
        issuer: env.issuer,
        checks: ["pkce", "state", "nonce"],
        authorization: { params: { scope: "openid profile" } },
        // `mw-admin-ui` is a public client in realm ops: PKCE, no client secret
        ...(env.clientSecret ? {} : { client: { token_endpoint_auth_method: "none" } }),
        [customFetch]: keycloakFetch(env),
      }),
    ],
    callbacks: {
      async jwt({ token, account, profile }) {
        if (account?.access_token) {
          return {
            name: (profile?.preferred_username as string | undefined) ?? token.name,
            sub: token.sub,
            accessToken: account.access_token,
            refreshToken: account.refresh_token,
            expiresAt: account.expires_at,
            roles: rolesOf(account.access_token),
          };
        }
        if (token.accessToken && !expiresSoon(token.expiresAt)) return token;
        if (token.error) return token;
        return refreshAccessToken(token, env);
      },
      // What /api/auth/session (and so the browser) may see: no token, ever.
      session({ session, token }) {
        return {
          expires: session.expires,
          user: { name: token.name ?? undefined },
          roles: token.roles ?? [],
          ...(token.error ? { error: token.error } : {}),
        } as typeof session;
      },
    },
    events: {
      async signOut(message) {
        await endKeycloakSession("token" in message ? message.token : null, env);
      },
    },
  };
}

export const { handlers, auth, signIn, signOut } = NextAuth(() => authConfig(authEnv()));
