// Every request: Auth.js reads (and when due refreshes) the encrypted session cookie; a page
// without a valid session goes to /login; every response gets a CSP with a fresh nonce
// (Next.js puts it on its own scripts when the request carries the CSP header).

import { NextResponse, type NextFetchEvent, type NextRequest } from "next/server";
import { auth } from "@/auth";
import { consolePath } from "@/lib/redirect";
import { contentSecurityPolicy } from "@/lib/security-headers";
import { BASE_PATH } from "@/lib/server/env";

const PUBLIC = [/^\/login$/, /^\/api\/auth(\/|$)/, /^\/api\/health$/];

const withAuth = auth((req) => {
  const nonce = Buffer.from(crypto.randomUUID()).toString("base64");
  const idpOrigin = new URL(process.env.AUTH_KEYCLOAK_ISSUER ?? "http://localhost").origin;
  const csp = contentSecurityPolicy(nonce, process.env.NODE_ENV === "development", idpOrigin);
  const pathname = consolePath(req.nextUrl.pathname, BASE_PATH);
  const { search } = req.nextUrl;

  let res: NextResponse;
  if (!PUBLIC.some((p) => p.test(pathname)) && (!req.auth || req.auth.error)) {
    const login = new URL(`${BASE_PATH}/login`, req.nextUrl.origin);
    login.searchParams.set("callbackUrl", `${pathname}${search}`);
    if (req.auth?.error) login.searchParams.set("error", "SessionExpired");
    res = NextResponse.redirect(login);
  } else {
    const headers = new Headers(req.headers);
    headers.set("x-nonce", nonce);
    headers.set("content-security-policy", csp);
    res = NextResponse.next({ request: { headers } });
  }
  res.headers.set("content-security-policy", csp);
  res.headers.set("cache-control", "no-store");
  return res;
});

type Handler = (req: NextRequest, ev: NextFetchEvent) => Promise<Response>;

// With a lazy Auth.js config (src/auth.ts) the wrapper resolves asynchronously.
export async function proxy(req: NextRequest, ev: NextFetchEvent): Promise<Response> {
  const handler = (await (withAuth as unknown as Promise<Handler> | Handler));
  return handler(req, ev);
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
