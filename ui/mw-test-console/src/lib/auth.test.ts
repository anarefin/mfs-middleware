import { describe, expect, it } from "vitest";
import { OTP_TEXT, PIN_RE, coolingUntil, detailOf, freshCandidate, inCooling } from "./auth";

describe("auth helpers", () => {
  it("reads the code of each OTP purpose from its SMS text only", () => {
    expect(OTP_TEXT.BIND.exec("Your Bits code to bind a new device is 123456. Valid 2 minutes.")?.[1]).toBe("123456");
    expect(OTP_TEXT.LOGIN.exec("Your Bits code to sign in and set your PIN is 654321. Valid 2 minutes.")?.[1]).toBe("654321");
    expect(OTP_TEXT.RESET.exec("Your Bits code to reset your PIN is 111222. Valid 2 minutes.")?.[1]).toBe("111222");
    expect(OTP_TEXT.RESET.exec("Your Bits code to bind a new device is 123456.")).toBeNull();
  });

  it("picks fresh wallets among core-mock's customers 01711000500..01711000999 only", () => {
    expect(freshCandidate(() => 0)).toBe("01711000500");
    expect(freshCandidate(() => 0.999999)).toBe("01711000999");
    expect(freshCandidate(() => 1)).toBe("01711000999");
    for (let i = 0; i < 50; i++) expect(freshCandidate()).toMatch(/^017110(00[5-9][0-9]{2})$/);
  });

  it("derives the new-device window from boundAt", () => {
    expect(coolingUntil("2026-11-15T10:00:00Z")).toBe("2026-11-16T10:00:00.000Z");
    expect(coolingUntil("nonsense")).toBeNull();
    const bound = "2026-11-15T10:00:00Z";
    expect(inCooling(bound, Date.parse("2026-11-15T10:00:01Z"))).toBe(true);
    expect(inCooling(bound, Date.parse("2026-11-16T09:59:59Z"))).toBe(true);
    expect(inCooling(bound, Date.parse("2026-11-16T10:00:00Z"))).toBe(false);
    expect(inCooling("nonsense")).toBe(false);
  });

  it("reads envelope details", () => {
    const body = { code: "MW-AUTH-040", details: [{ field: "triesLeft", issue: "2" }] };
    expect(detailOf(body, "triesLeft")).toBe("2");
    expect(detailOf(body, "retryAfter")).toBeUndefined();
    expect(detailOf(null, "triesLeft")).toBeUndefined();
  });

  it("knows the 5-digit PIN", () => {
    expect(PIN_RE.test("48207")).toBe(true);
    expect(PIN_RE.test("1234")).toBe(false);
  });
});
