import { describe, expect, it } from "vitest";
import {
  BILLERS, BILLER_CATEGORIES, CHECKOUT_MERCHANTS, DEFAULT_CHECKOUT_MERCHANT, GENERATED_RANGES, ORGS, categoriesOf, categoryLabel, checkoutMerchant,
  filterBillers, groupBillers, parseBillerList, protocolOf, rangeOf,
} from "./seed-catalogue";

describe("seed catalogue", () => {
  it("names core-mock's generated ranges and finds a number in them", () => {
    expect(GENERATED_RANGES.map((r) => [r.prefix, r.count])).toEqual([["0171100", 1000], ["0181100", 50], ["0161100", 10], ["0191100", 20], ["0131100", 3]]);
    expect(rangeOf("01711000001")?.type).toBe("CUSTOMER");
    expect(rangeOf("01711001000")?.type).toBe("CUSTOMER");
    expect(rangeOf("01711001001")).toBeUndefined();
    expect(rangeOf("01811000050")?.type).toBe("AGENT");
    expect(rangeOf("01811000051")).toBeUndefined();
    expect(rangeOf("01311000003")?.type).toBe("ORG");
    expect(rangeOf("01611000010")?.type).toBe("DISTRIBUTOR");
    expect(rangeOf("01711000000")).toBeUndefined();
    expect(rangeOf("nope")).toBeUndefined();
  });

  it("has the orgs and billers of the services' configs", () => {
    expect(ORGS.map((o) => [o.orgId, o.walletId])).toEqual([["ORG001", "01311000001"], ["ORG002", "01311000002"]]);
    expect(BILLERS.map((b) => [b.code, b.category, b.protocol])).toEqual([
      ["BREB", "ELECTRICITY", "REST"], ["BTCL", "TELECOM", "SOAP"], ["CWASA", "WATER", "REST"], ["DESCO", "ELECTRICITY", "REST"],
      ["DPDC", "ELECTRICITY", "REST"], ["JGTDSL", "GAS", "SOAP"], ["KGDCL", "GAS", "SOAP"], ["LINK3", "INTERNET", "SOAP"],
      ["TITAS", "GAS", "REST"], ["WASA", "WATER", "REST"],
    ]);
    expect(BILLERS.every((b) => b.partnerId === "billagg")).toBe(true);
    expect(BILLERS.find((b) => b.code === "DESCO")?.name).toBe("Dhaka Electric Supply Company");
    expect(BILLERS.find((b) => b.code === "KGDCL")?.name).toBe("Karnaphuli Gas Distribution Company");
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

describe("biller categories, search and groups", () => {
  const list = [
    { code: "TITAS", name: "Titas Gas", category: "GAS" },
    { code: "DESCO", name: "Dhaka Electric Supply Company", category: "ELECTRICITY" },
    { code: "KGDCL", name: "Karnaphuli Gas Distribution Company", category: "GAS" },
    { code: "XNEW", name: "A new biller", category: "LOTTERY" },
    { code: "NOCAT", name: "No category" },
  ];

  it("has the fixed category order and the en / bn labels", () => {
    expect([...BILLER_CATEGORIES]).toEqual(["ELECTRICITY", "WATER", "GAS", "TELECOM", "INTERNET"]);
    expect(BILLER_CATEGORIES.map((c) => categoryLabel(c))).toEqual(["Electricity", "Water", "Gas", "Telecom", "Internet"]);
    expect(BILLER_CATEGORIES.map((c) => categoryLabel(c, "bn"))).toEqual(["বিদ্যুৎ", "পানি", "গ্যাস", "টেলিফোন", "ইন্টারনেট"]);
    expect(categoryLabel("LOTTERY")).toBe("LOTTERY");
    expect(categoryLabel(undefined)).toBe("Other");
  });

  it("knows each catalogue biller's protocol, none for an unknown code", () => {
    expect(protocolOf("KGDCL")).toBe("SOAP");
    expect(protocolOf("DESCO")).toBe("REST");
    expect(protocolOf("XNEW")).toBeUndefined();
    expect(protocolOf("toString")).toBeUndefined();
  });

  it("lists the categories present, in the fixed order, unknown ones after", () => {
    expect(categoriesOf(list)).toEqual(["ELECTRICITY", "GAS", "LOTTERY", "OTHER"]);
    expect(categoriesOf(BILLERS)).toEqual([...BILLER_CATEGORIES]);
    expect(categoriesOf([])).toEqual([]);
  });

  it("filters by category and by code or name, case-insensitive, sorted by code", () => {
    expect(filterBillers(list, "ALL", "").map((b) => b.code)).toEqual(["DESCO", "KGDCL", "NOCAT", "TITAS", "XNEW"]);
    expect(filterBillers(list, "GAS", "").map((b) => b.code)).toEqual(["KGDCL", "TITAS"]);
    expect(filterBillers(list, "GAS", "kg").map((b) => b.code)).toEqual(["KGDCL"]);
    expect(filterBillers(list, "ALL", "  dhaka ").map((b) => b.code)).toEqual(["DESCO"]);
    expect(filterBillers(list, "ALL", "GAS").map((b) => b.code)).toEqual(["KGDCL", "TITAS"]);
    expect(filterBillers(list, "OTHER", "").map((b) => b.code)).toEqual(["NOCAT"]);
    expect(filterBillers(list, "ELECTRICITY", "titas")).toEqual([]);
    expect(filterBillers(BILLERS, "GAS", "").map((b) => b.code)).toEqual(["JGTDSL", "KGDCL", "TITAS"]);
    expect(filterBillers(BILLERS, "ELECTRICITY", "").map((b) => b.code)).toEqual(["BREB", "DESCO", "DPDC"]);
  });

  it("groups by category in the fixed order, each group sorted by code", () => {
    expect(groupBillers(list).map((g) => [g.category, g.label, g.billers.map((b) => b.code)])).toEqual([
      ["ELECTRICITY", "Electricity", ["DESCO"]], ["GAS", "Gas", ["KGDCL", "TITAS"]], ["LOTTERY", "LOTTERY", ["XNEW"]], ["OTHER", "Other", ["NOCAT"]],
    ]);
    expect(groupBillers(BILLERS).map((g) => g.billers.length)).toEqual([3, 2, 3, 1, 1]);
  });

  it("reads the app API's biller list defensively", () => {
    expect(parseBillerList({ billers: [{ code: "DESCO", name: "Dhaka Electric Supply Company", category: "ELECTRICITY" }, { code: "X" }, "junk", { code: "KGDCL", name: "K", category: 3 }] }))
      .toEqual([{ code: "DESCO", name: "Dhaka Electric Supply Company", category: "ELECTRICITY" }, { code: "X", name: "X" }, { code: "KGDCL", name: "K" }]);
    expect(parseBillerList({ billers: [] })).toEqual([]);
    expect(parseBillerList(null)).toBeNull();
    expect(parseBillerList({ billers: "x" })).toBeNull();
    expect(parseBillerList({ code: "MW-SYS-011" })).toBeNull();
  });
});
