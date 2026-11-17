import { describe, expect, it } from "vitest";
import { BILLERS, CHECKOUT_MERCHANTS, DEFAULT_CHECKOUT_MERCHANT, GENERATED_RANGES, ORGS, checkoutMerchant, rangeOf } from "./seed-catalogue";

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

  it("has the checkout merchants of mw-checkout's config, each with its own key id and secret", () => {
    expect(CHECKOUT_MERCHANTS.map((m) => [m.id, m.keyId, m.walletId, m.name])).toEqual([
      ["merchant_demo", "merchant-demo", "01911000001", "Karim Traders"],
      ["merchant_pharmacy", "merchant-pharmacy", "01911000002", "Lalbagh Pharmacy"],
      ["merchant_fashion", "merchant-fashion", "01911000003", "Gulshan Fashion House"],
    ]);
    expect(new Set(CHECKOUT_MERCHANTS.map((m) => m.secretEnv)).size).toBe(CHECKOUT_MERCHANTS.length);
    for (const m of CHECKOUT_MERCHANTS) {
      expect(m.secretEnv).toMatch(/^CHECKOUT_[A-Z]+_MERCHANT_SECRET$/);
      expect(rangeOf(m.walletId)?.type).toBe("MERCHANT");
    }
  });

  it("resolves a merchant id, falling back to the default for anything else", () => {
    expect(DEFAULT_CHECKOUT_MERCHANT.id).toBe("merchant_demo");
    expect(checkoutMerchant("merchant_fashion").name).toBe("Gulshan Fashion House");
    expect(checkoutMerchant("merchant_unknown")).toBe(DEFAULT_CHECKOUT_MERCHANT);
    expect(checkoutMerchant(undefined)).toBe(DEFAULT_CHECKOUT_MERCHANT);
    expect(checkoutMerchant("toString")).toBe(DEFAULT_CHECKOUT_MERCHANT);
  });
});
