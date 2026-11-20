import { describe, expect, it } from "vitest";
import { previewHeadline, previewRows } from "./bulk-preview";

describe("previewHeadline", () => {
  it("names the status, the valid and invalid rows and the total of the valid ones", () => {
    expect(
      previewHeadline({ batchId: "b-1", status: "PREVIEW", rowCount: 5, validCount: 4, invalidCount: 1, totalAmount: { valueMinor: 400, currency: "BDT" } }),
    ).toBe("batch b-1: PREVIEW, 5 rows (4 valid, 1 invalid), Tk 4.00 to pay");
  });
  it("still reads an answer without counts (an older server or an error)", () => {
    expect(previewHeadline({ batchId: "b-2", status: "PENDING_APPROVAL" })).toBe("batch b-2: PENDING_APPROVAL");
    expect(previewHeadline(undefined)).toBe("batch ?: ?");
  });
});

describe("previewRows", () => {
  it("lists every row with its line, and the codes of an invalid one", () => {
    expect(
      previewRows({
        rows: [
          { line: 2, msisdn: "0171****001", amount: { valueMinor: 100, currency: "BDT" }, reference: "R1", valid: true },
          { line: 3, reference: "R2", valid: false, errors: ["MSISDN_INVALID", "AMOUNT_INVALID"] },
        ],
      }),
    ).toEqual(["line 2: 0171****001 Tk 1.00 R1", "line 3: invalid (MSISDN_INVALID, AMOUNT_INVALID) R2"]);
  });
  it("is empty without rows", () => {
    expect(previewRows({})).toEqual([]);
    expect(previewRows(null)).toEqual([]);
  });
});
