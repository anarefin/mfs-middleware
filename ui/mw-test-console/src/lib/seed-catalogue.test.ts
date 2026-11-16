import { describe, expect, it } from "vitest";
import { BILLERS, GENERATED_RANGES, ORGS, rangeOf } from "./seed-catalogue";

describe("seed catalogue", () => {
  it("names core-mock's generated ranges and finds a number in them", () => {
    expect(GENERATED_RANGES.map((r) => [r.prefix, r.count])).toEqual([["0171100", 1000], ["0181100", 50], ["0191100", 20], ["0131100", 2]]);
    expect(rangeOf("01711000001")?.type).toBe("CUSTOMER");
    expect(rangeOf("01711001000")?.type).toBe("CUSTOMER");
    expect(rangeOf("01711001001")).toBeUndefined();
    expect(rangeOf("01811000050")?.type).toBe("AGENT");
    expect(rangeOf("01811000051")).toBeUndefined();
    expect(rangeOf("01311000002")?.type).toBe("ORG");
    expect(rangeOf("01711000000")).toBeUndefined();
    expect(rangeOf("nope")).toBeUndefined();
  });

  it("has the orgs and billers of the services' configs", () => {
    expect(ORGS.map((o) => [o.orgId, o.walletId])).toEqual([["ORG001", "01311000001"], ["ORG002", "01311000002"]]);
    expect(BILLERS.map((b) => b.code)).toEqual(["DESCO", "DPDC", "WASA", "TITAS"]);
  });
});
