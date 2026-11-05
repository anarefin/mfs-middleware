// Small OIDC helpers for the Keycloak `ops` login (src/auth.ts). No secrets here.

/**
 * Keycloak is reached by the browser on the public URL (through APISIX) and by this server
 * inside the stack. Keycloak derives the token issuer from the request host (KC_PROXY_HEADERS
 * xforwarded), so a server-side call to the internal URL carries the public host as
 * X-Forwarded-*: tokens then carry the same `iss` whichever way they were asked for.
 * Returns null when the call is not for the issuer or no internal URL is configured.
 */
export function internalRequest(url: string, issuer: string, internalBase: string | undefined)
  : { url: string; headers: Record<string, string> } | null {
  if (!internalBase) return null;
  const pub = new URL(issuer);
  const target = new URL(url);
  if (target.origin !== pub.origin) return null;
  const base = internalBase.replace(/\/+$/, "");
  const port = pub.port || (pub.protocol === "https:" ? "443" : "80");
  return {
    url: `${base}${target.pathname}${target.search}`,
    headers: {
      "x-forwarded-host": pub.hostname,
      "x-forwarded-proto": pub.protocol.replace(":", ""),
      "x-forwarded-port": port,
    },
  };
}

/** Realm roles (`realm_access.roles`) of an access token, read for display; mw-admin-api checks them. */
export function rolesOf(accessToken: string | undefined): string[] {
  const part = accessToken?.split(".")[1];
  if (!part) return [];
  try {
    const claims = JSON.parse(Buffer.from(part, "base64url").toString("utf8")) as {
      realm_access?: { roles?: unknown };
    };
    const roles = claims.realm_access?.roles;
    return Array.isArray(roles) ? roles.filter((r): r is string => typeof r === "string") : [];
  } catch {
    return [];
  }
}

/** Roles that may see MSISDNs unmasked (spec 15.4); mw-admin-api decides, this only shows the link. */
const REVEAL_ROLES = new Set(["compliance-officer", "auditor"]);

export function canReveal(roles: readonly string[]): boolean {
  return roles.some((r) => REVEAL_ROLES.has(r));
}

/** true when the access token (expires_at in epoch seconds) expires within 30 s. */
export function expiresSoon(expiresAt: number | undefined, nowMs: number = Date.now()): boolean {
  return expiresAt === undefined || expiresAt * 1000 - nowMs < 30_000;
}
