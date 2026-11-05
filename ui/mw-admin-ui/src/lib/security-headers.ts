// Response security headers (spec 15.1, 18.6). The CSP carries a per-request nonce and is set by
// src/proxy.ts (Next.js puts the nonce on its own scripts); the static headers come from
// next.config.ts for every response, static files included.

/**
 * @param nonce   fresh per request
 * @param dev     `next dev` needs eval for its tooling; a production build never gets it
 * @param idpOrigin origin of the Keycloak login: the sign-in and sign-out forms redirect there, and
 *                browsers apply form-action to the redirect of a form submission
 */
export function contentSecurityPolicy(nonce: string, dev: boolean, idpOrigin: string): string {
  return [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${dev ? " 'unsafe-eval'" : ""}`,
    // React/Next set inline style attributes; no external styles
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data:",
    "font-src 'self'",
    "connect-src 'self'",
    "object-src 'none'",
    "base-uri 'self'",
    `form-action 'self' ${idpOrigin}`,
    "frame-ancestors 'none'",
  ].join("; ");
}

export const STATIC_SECURITY_HEADERS: { key: string; value: string }[] = [
  { key: "Strict-Transport-Security", value: "max-age=31536000; includeSubDomains" },
  { key: "X-Frame-Options", value: "DENY" },
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "Referrer-Policy", value: "no-referrer" },
  { key: "Permissions-Policy", value: "camera=(), microphone=(), geolocation=(), payment=()" },
  { key: "Cross-Origin-Opener-Policy", value: "same-origin" },
];
