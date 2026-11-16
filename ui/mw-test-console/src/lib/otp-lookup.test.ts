import { describe, expect, it } from "vitest";
import { latestOtps } from "./otp-lookup";

describe("latestOtps", () => {
  const now = Date.parse("2026-11-15T10:00:00Z");
  const sms = [
    { destination: "01711000501", text: "Your Bits code to bind a new device is 111111", receivedAt: "2026-11-15T09:58:00Z" },
    { destination: "01711000501", text: "Your Bits code to bind a new device is 222222", receivedAt: "2026-11-15T09:59:30Z" },
    { destination: "01711000501", text: "Your Bits code to sign in and set your PIN is 333333", receivedAt: "2026-11-15T09:50:00Z" },
    { destination: "01711000501", text: "You have received Tk 10 from 0171****002", receivedAt: "2026-11-15T09:59:59Z" },
  ];

  it("keeps the newest code per purpose with its age", () => {
    expect(latestOtps(sms, now)).toEqual([
      { purpose: "BIND", code: "222222", receivedAt: "2026-11-15T09:59:30Z", ageS: 30 },
      { purpose: "LOGIN", code: "333333", receivedAt: "2026-11-15T09:50:00Z", ageS: 600 },
      { purpose: "RESET", code: null, receivedAt: null, ageS: null },
    ]);
  });

  it("takes the last row when the simulator gives no times (it lists in arrival order)", () => {
    const r = latestOtps([{ text: "Your Bits code to reset your PIN is 444444" }, { text: "Your Bits code to reset your PIN is 555555" }], now);
    expect(r.find((x) => x.purpose === "RESET")).toEqual({ purpose: "RESET", code: "555555", receivedAt: null, ageS: null });
  });
});
