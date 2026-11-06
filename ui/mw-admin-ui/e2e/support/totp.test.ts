import { describe, expect, it } from "vitest";
import { totp, totpStep } from "./totp";

// RFC 6238 appendix B, SHA-256 seed (32 ASCII bytes); the realm uses 6 digits, so the last 6 of
// the published 8-digit values.
const RFC_SECRET = "12345678901234567890123456789012";

describe("TOTP as realm ops checks it (HmacSHA256, 6 digits, 30 s, secret as UTF-8 bytes)", () => {
  it("matches the RFC 6238 SHA-256 vectors", () => {
    expect(totp(RFC_SECRET, totpStep(59_000))).toBe("119246");
    expect(totp(RFC_SECRET, totpStep(1_111_111_109_000))).toBe("084774");
    expect(totp(RFC_SECRET, totpStep(1_111_111_111_000))).toBe("062674");
    expect(totp(RFC_SECRET, totpStep(1_234_567_890_000))).toBe("819424");
    expect(totp(RFC_SECRET, totpStep(20_000_000_000_000))).toBe("737706");
  });

  it("counts 30 s steps", () => {
    expect(totpStep(29_999)).toBe(0);
    expect(totpStep(30_000)).toBe(1);
  });
});
