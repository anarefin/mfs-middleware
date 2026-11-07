import { describe, expect, it } from "vitest";
import { contentSecurityPolicy, STATIC_SECURITY_HEADERS } from "./security-headers";

describe("security headers", () => {
  it("puts the nonce in script-src and allows eval only in dev", () => {
    expect(contentSecurityPolicy("abc", false)).toContain("script-src 'self' 'nonce-abc' 'strict-dynamic';");
    expect(contentSecurityPolicy("abc", false)).not.toContain("unsafe-eval");
    expect(contentSecurityPolicy("abc", true)).toContain("'unsafe-eval'");
    expect(contentSecurityPolicy("abc", false)).toContain("frame-ancestors 'none'");
  });
  it("sends HSTS, DENY framing and noindex on every response", () => {
    const keys = STATIC_SECURITY_HEADERS.map((h) => h.key);
    expect(keys).toEqual(expect.arrayContaining(["Strict-Transport-Security", "X-Frame-Options", "X-Robots-Tag"]));
  });
});
