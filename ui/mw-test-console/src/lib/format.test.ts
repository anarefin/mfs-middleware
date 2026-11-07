import { describe, expect, it } from "vitest";
import { asksForPin, bulkCsv, formatMinor, normaliseMsisdn, takaToMinor } from "./format";

describe("takaToMinor", () => {
  it("reads whole and decimal taka", () => {
    expect(takaToMinor("10")).toBe(1000);
    expect(takaToMinor("10.5")).toBe(1050);
    expect(takaToMinor(" 20.13 ")).toBe(2013);
  });
  it("refuses zero, negatives, three decimals and text", () => {
    for (const bad of ["0", "-1", "1.234", "abc", "", "0.00"]) expect(takaToMinor(bad)).toBeNull();
  });
});

describe("formatMinor", () => {
  it("formats poisha as taka", () => {
    expect(formatMinor(123450)).toBe("Tk 1,234.50");
    expect(formatMinor(-2500)).toBe("-Tk 25.00");
    expect(formatMinor(0)).toBe("Tk 0.00");
  });
});

describe("normaliseMsisdn", () => {
  it("accepts the local and international forms", () => {
    expect(normaliseMsisdn("01711000001")).toBe("01711000001");
    expect(normaliseMsisdn("8801711000001")).toBe("01711000001");
    expect(normaliseMsisdn("+880 1711-000001")).toBe("01711000001");
  });
  it("refuses other numbers", () => {
    for (const bad of ["0121100000", "01211000001", "1711000001", "abc"]) expect(normaliseMsisdn(bad)).toBeNull();
  });
});

describe("bulkCsv", () => {
  it("writes a header and one row per payout to seeded customers other than the personas", () => {
    const lines = bulkCsv(3, "1.00", "r").trim().split("\n");
    expect(lines).toEqual(["msisdn,amount,reference", "01711000101,1.00,TC-r-1", "01711000102,1.00,TC-r-2", "01711000103,1.00,TC-r-3"]);
  });
});

describe("asksForPin", () => {
  it("spots the PIN screen in English and Bangla", () => {
    expect(asksForPin("Enter your Bits PIN")).toBe(true);
    expect(asksForPin("আপনার Bits পিন দিন")).toBe(true);
    expect(asksForPin("Enter amount (Tk)")).toBe(false);
  });
});
