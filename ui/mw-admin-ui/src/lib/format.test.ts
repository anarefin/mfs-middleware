import { describe, expect, it } from "vitest";
import { formatAmount, formatTime, partyLabel } from "./format";

describe("format", () => {
  it("shows poisha as taka with two decimals", () => {
    expect(formatAmount({ valueMinor: 150050, currency: "BDT" })).toBe("BDT 1,500.50");
    expect(formatAmount({ valueMinor: 5, currency: "BDT" })).toBe("BDT 0.05");
    expect(formatAmount(undefined)).toBe("");
  });

  it("shows instants in UTC to the second", () => {
    expect(formatTime("2026-11-05T10:15:30.123456Z")).toBe("2026-11-05 10:15:30 UTC");
    expect(formatTime(undefined)).toBe("");
    expect(formatTime("garbage")).toBe("garbage");
  });

  it("labels a party by its type and (masked) id", () => {
    expect(partyLabel({ type: "CUSTOMER", id: "0171****789", name: "R." })).toBe("CUSTOMER 0171****789 (R.)");
    expect(partyLabel({ type: "MERCHANT", id: "M-1" })).toBe("MERCHANT M-1");
    expect(partyLabel(undefined)).toBe("");
  });
});
