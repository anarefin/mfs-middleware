import { describe, expect, it } from "vitest";
import { contentSecurityPolicy, STATIC_SECURITY_HEADERS } from "./security-headers";

describe("security headers (spec 15.1)", () => {
  it("allows scripts only with the request's nonce, and nothing framed or loaded from elsewhere", () => {
    const csp = contentSecurityPolicy("n0nce", false, "https://localhost");
    expect(csp).toContain("default-src 'self'");
    expect(csp).toContain("script-src 'self' 'nonce-n0nce' 'strict-dynamic'");
    expect(csp).toContain("frame-ancestors 'none'");
    expect(csp).toContain("object-src 'none'");
    // the sign-in form redirects to Keycloak, and browsers apply form-action to that redirect
    expect(csp).toContain("form-action 'self' https://localhost");
    expect(csp).not.toContain("unsafe-eval");
  });

  it("lets the dev server evaluate scripts, never a production build", () => {
    expect(contentSecurityPolicy("n", true, "http://localhost:4010")).toContain("'unsafe-eval'");
  });

  it("sends HSTS and X-Frame-Options DENY on every response", () => {
    const h = Object.fromEntries(STATIC_SECURITY_HEADERS.map((x) => [x.key, x.value]));
    expect(h["Strict-Transport-Security"]).toBe("max-age=31536000; includeSubDomains");
    expect(h["X-Frame-Options"]).toBe("DENY");
    expect(h["X-Content-Type-Options"]).toBe("nosniff");
    expect(h["Referrer-Policy"]).toBe("no-referrer");
  });
});
