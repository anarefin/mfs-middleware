import { describe, expect, it } from "vitest";
import { parseRuleForm, ruleCondition, templatesTab, templatesTabHref } from "./notify-rules";

const form = {
  ruleId: "p2p-success-receipt", eventType: "P2P", outcome: "SUCCESS", customerType: "CUSTOMER",
  recipient: "DEBIT_PARTY", channels: ["SMS", "PUSH"], templateCode: "TXN_P2P_SENT", enabled: "true", reason: "push too",
};

describe("templates page tabs (ticket 133c)", () => {
  it("defaults to the templates tab and links the rules tab", () => {
    expect(templatesTab(undefined)).toBe("templates");
    expect(templatesTab("rules")).toBe("rules");
    expect(templatesTab("other")).toBe("templates");
    expect(templatesTabHref("rules")).toBe("/templates?tab=rules");
    expect(templatesTabHref("templates")).toBe("/templates");
  });
});

describe("rule form (as mw-admin-api checks a notify-rule)", () => {
  it("builds the change request body, channels in SMS, EMAIL, PUSH order", () => {
    const r = parseRuleForm({ ...form, channels: ["PUSH", "SMS"] });
    expect(r).toEqual({
      ok: true,
      value: {
        ruleId: "p2p-success-receipt",
        body: {
          eventType: "P2P", outcome: "SUCCESS", customerType: "CUSTOMER", recipient: "DEBIT_PARTY",
          channels: ["SMS", "PUSH"], templateCode: "TXN_P2P_SENT", enabled: true, reason: "push too",
        },
      },
    });
  });

  it("an unticked enabled box stops the rule; an empty customer type is any", () => {
    const r = parseRuleForm({ ...form, enabled: undefined, customerType: "" });
    expect(r.ok && r.value.body.enabled).toBe(false);
    expect(r.ok && r.value.body.customerType).toBe("*");
  });

  it("refuses what the API refuses, naming the field", () => {
    expect(parseRuleForm({ ...form, ruleId: "Bad_Id" })).toMatchObject({ ok: false, field: "ruleId" });
    expect(parseRuleForm({ ...form, eventType: "p2p" })).toMatchObject({ ok: false, field: "eventType" });
    expect(parseRuleForm({ ...form, outcome: "DONE" })).toMatchObject({ ok: false, field: "outcome" });
    expect(parseRuleForm({ ...form, recipient: "ANYONE" })).toMatchObject({ ok: false, field: "recipient" });
    expect(parseRuleForm({ ...form, channels: [] })).toMatchObject({ ok: false, field: "channels" });
    expect(parseRuleForm({ ...form, channels: ["FAX"] })).toMatchObject({ ok: false, field: "channels" });
    expect(parseRuleForm({ ...form, templateCode: "x" })).toMatchObject({ ok: false, field: "templateCode" });
    expect(parseRuleForm({ ...form, reason: " " })).toMatchObject({ ok: false, field: "reason" });
  });
});

describe("rule condition as mw-notify applies it", () => {
  it("reads the outcome group and the event type", () => {
    expect(ruleCondition("P2P", "SUCCESS")).toBe("P2P: COMPLETED");
    expect(ruleCondition("CASH_IN", "FAILURE")).toBe("CASH_IN: FAILED or REJECTED");
    expect(ruleCondition("TOPUP", "PROCESSING")).toBe("TOPUP: UNKNOWN or MANUAL_REVIEW");
  });
});
