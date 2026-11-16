import { describe, expect, it } from "vitest";
import { APP_PERSONAS, FLOWS_BY_ROLE, PORTAL_PERSONAS, TXN_TYPES } from "./personas";

describe("personas", () => {
  it("label the users with their real names, ids unchanged", () => {
    expect(APP_PERSONAS.map((p) => [p.user, p.msisdn])).toEqual([
      ["customer01", "01711000001"], ["customer02", "01711000002"], ["agent01", "01811000001"], ["merchant01", "01911000001"], ["merchant02", "01911000002"],
    ]);
    expect(APP_PERSONAS[0]?.label).toBe("Rahim Uddin (customer01)");
    expect(PORTAL_PERSONAS.find((p) => p.user === "org-maker01")?.label).toMatch(/^Mizanur Rahman \(org-maker01/);
  });

  it("gives each role its flows: a customer pays, an agent cashes in, a merchant only collects", () => {
    expect(FLOWS_BY_ROLE.customer).toEqual(["P2P", "CASH_OUT", "MERCHANT_PAY", "TOPUP", "BILL_PAY", "ADD_MONEY_BANK"]);
    expect(FLOWS_BY_ROLE.agent).toEqual(["CASH_IN"]);
    expect(FLOWS_BY_ROLE.merchant).toEqual([]);
    const all = [...FLOWS_BY_ROLE.customer, ...FLOWS_BY_ROLE.agent].sort();
    expect(all).toEqual([...TXN_TYPES].sort());
  });
});
