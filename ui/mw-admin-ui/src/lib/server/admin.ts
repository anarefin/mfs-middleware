// Server-only wiring: the operator's access token (from the encrypted session cookie) and the
// admin API client. Importing this from a client component fails the build ("server-only").
import "server-only";

import { cookies, headers } from "next/headers";
import { getToken } from "next-auth/jwt";
import { refreshAccessToken } from "@/auth";
import { createAdminApi } from "@/lib/admin-api";
import { expiresSoon } from "@/lib/oidc";
import { adminApiUrl, authEnv } from "./env";

const SECURE_COOKIE = "__Secure-authjs.session-token";

/**
 * The access token of the signed-in operator, for calls this server makes on their behalf.
 * The proxy refreshes an expiring token and stores it in the cookie of its response; this request
 * still carries the old cookie, so an expiring token is refreshed here too (in memory only).
 */
export async function operatorToken(): Promise<string | null> {
  const env = authEnv();
  const jar = await cookies();
  const secureCookie = jar.getAll().some((c) => c.name === SECURE_COOKIE || c.name.startsWith(`${SECURE_COOKIE}.`));
  const token = await getToken({ req: { headers: await headers() }, secret: env.secret, secureCookie });
  if (!token?.accessToken || token.error) return null;
  if (!expiresSoon(token.expiresAt)) return token.accessToken;
  const fresh = await refreshAccessToken(token, env);
  return fresh.accessToken ?? null;
}

export function adminApi() {
  return createAdminApi({ baseUrl: adminApiUrl() });
}
