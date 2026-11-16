import { describe, expect, it } from "vitest";
import { CUSTOM, GOOD_PIN, WRONG_PIN, counterpartyOptions, defaultCounterparty, scenarios } from "./app-flow-options";
import { appUsers } from "./app-users";
import { TXN_TYPES } from "./personas";
import { BILLERS } from "./seed-catalogue";
import { BILLER_OPTIONS } from "./app-flow-options";

const users = appUsers(null);

describe("counterpartyOptions", () => {
  it("offers wallets of the role the flow pays, never the signed-in one", () => {
    expect(counterpartyOptions("P2P", "01711000001", users).map((o) => o.value)).toEqual(["01711000002"]);
    expect(counterpartyOptions("CASH_IN", "01811000001", users).map((o) => o.value)).toEqual(["01711000001", "01711000002"]);
    expect(counterpartyOptions("CASH_OUT", "01711000001", users).map((o) => o.value)).toEqual(["01811000001"]);
    expect(counterpartyOptions("MERCHANT_PAY", "01711000001", users).map((o) => o.value)).toEqual(["01911000001", "01911000002"]);
  });

  it("offers the simulators' magic numbers for the partner flows", () => {
    expect(counterpartyOptions("TOPUP", "01711000001", users).map((o) => o.value)).toContain("01711000999");
    expect(counterpartyOptions("BILL_PAY", undefined, users).map((o) => o.value)).toEqual(["1234567", "1234404", "1234000", "1234999"]);
    expect(counterpartyOptions("ADD_MONEY_BANK", undefined, users).map((o) => o.value)).toEqual(["1234567890123", "1234567890999"]);
    for (const t of TXN_TYPES) expect(counterpartyOptions(t, undefined, users).some((o) => o.value === CUSTOM), t).toBe(false);
  });

  it("defaults to the flow's documented counterparty, else the first other wallet", () => {
    expect(defaultCounterparty("P2P", "01711000001", users)).toBe("01711000002");
    expect(defaultCounterparty("P2P", "01711000002", users)).toBe("01711000001");
    expect(defaultCounterparty("CASH_OUT", "01711000001", users)).toBe("01811000001");
    expect(defaultCounterparty("BILL_PAY", "01711000001", users)).toBe("1234567");
  });

  it("lists the billers of the catalogue", () => {
    expect(BILLER_OPTIONS.map((o) => o.value)).toEqual(BILLERS.map((b) => b.code));
  });
});

describe("scenarios", () => {
  it("every flow has a happy path and a wrong PIN, labels unique", () => {
    for (const t of TXN_TYPES) {
      const c = scenarios(t, "01711000002");
      expect(new Set(c.map((x) => x.label)).size, t).toBe(c.length);
      expect(c[0]?.values.pin, t).toBe(GOOD_PIN);
      expect(c.some((x) => x.values.pin === WRONG_PIN), t).toBe(true);
    }
  });

  it("uses the simulators' magic values only where the flow has a partner", () => {
    const amounts = (t: (typeof TXN_TYPES)[number]) => scenarios(t, "x").map((c) => c.values.amount);
    expect(amounts("P2P").some((a) => /\.(13|77)$/.test(a))).toBe(false);
    expect(amounts("TOPUP")).toEqual(expect.arrayContaining(["20.13", "20.77"]));
    expect(scenarios("BILL_PAY", "x").map((c) => c.values.counterparty)).toEqual(expect.arrayContaining(["1234999", "1234404", "1234000"]));
    expect(scenarios("ADD_MONEY_BANK", "x").map((c) => c.values.counterparty)).toContain("1234567890999");
  });
});
