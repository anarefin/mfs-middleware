import { describe, expect, it } from "vitest";
import { TOTP_STEP_MS, totpWindow } from "./totp-window";

describe("totpWindow", () => {
  it("names the 30 s step and the time left in it", () => {
    expect(TOTP_STEP_MS).toBe(30000);
    expect(totpWindow(0)).toEqual({ step: 0, remainingMs: 30000, remainingS: 30, nextAtMs: 30000 });
    expect(totpWindow(29_001)).toMatchObject({ step: 0, remainingMs: 999, remainingS: 1, nextAtMs: 30000 });
    expect(totpWindow(30_000)).toMatchObject({ step: 1, remainingMs: 30000, remainingS: 30 });
    expect(totpWindow(61_500)).toMatchObject({ step: 2, remainingS: 29, nextAtMs: 90000 });
  });
});
