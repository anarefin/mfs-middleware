import { describe, expect, it } from "vitest";
import { redact } from "./redact";

describe("redact", () => {
  it("cuts tokens and ciphertexts at any depth and keeps the rest", () => {
    const out = redact({ access_token: "a".repeat(40), body: { encryptedPin: { keyId: "k", ciphertext: "c".repeat(30) } }, list: [{ id_token: "x".repeat(20) }], n: 1 });
    expect(out).toEqual({
      access_token: "aaaaaaaaaaaa… (40 chars)",
      body: { encryptedPin: { keyId: "k", ciphertext: "cccccccccccc… (30 chars)" } },
      list: [{ id_token: "xxxxxxxxxxxx… (20 chars)" }],
      n: 1,
    });
  });
  it("passes plain values through", () => {
    expect(redact("text")).toBe("text");
    expect(redact(null)).toBeNull();
  });
});
