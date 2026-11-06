import { describe, expect, it } from "vitest";
import { maskParam, parseTemplateKey, placeholderNames, renderPreview, sampleParams, smsInfo, templateProblems } from "./templates";

describe("template preview (spec 10.3: params masked by name before rendering)", () => {
  it("renders a Bangla SMS with sample params, the personal ones masked", () => {
    const body = "{{counterparty}} নম্বরে {{amount}} টাকা পাঠানো হয়েছে। TxnID {{txnId}}";
    const params = sampleParams(placeholderNames(body));
    const out = renderPreview(body, params);
    expect(out.text).toBe(
      "0181****321 নম্বরে 1500.00 টাকা পাঠানো হয়েছে। TxnID 0193a1b2-0000-7000-8000-000000000001",
    );
    expect(out.missing).toEqual([]);
  });

  it("masks by name as mw-notify's ParamMasker does", () => {
    expect(maskParam("msisdn", "01712345678")).toBe("0171****678");
    expect(maskParam("Receiver", "01812345321")).toBe("0181****321");
    expect(maskParam("accountNumber", "1234567890123")).toBe("****0123");
    expect(maskParam("nid", "19901234567890123")).toBe("****123");
    expect(maskParam("senderName", "rahim uddin")).toBe("R.");
    expect(maskParam("pin", "1234")).toBe("****");
    expect(maskParam("msisdn", "0171****678")).toBe("0171****678");
    expect(maskParam("otp", "123456")).toBe("123456");
  });

  it("an operator-typed sample MSISDN is masked too, a missing param is listed", () => {
    const out = renderPreview("{{msisdn}}: {{otp}} {{unknownThing}}", { msisdn: "01999888777", otp: "654321" });
    expect(out.text).toBe("0199****777: 654321 {{unknownThing}}");
    expect(out.missing).toEqual(["unknownThing"]);
  });

  it("lists each plain placeholder once, in order", () => {
    expect(placeholderNames("{{a}} {{b_1}} {{a}} {{ bad }} {{#x}}")).toEqual(["a", "b_1"]);
  });
});

describe("template checks (mw-admin-api TemplateValidator)", () => {
  it("accepts a plain SMS", () => {
    expect(templateProblems("SMS", { body: "কোড {{otp}}", senderId: "Bits" })).toEqual([]);
  });

  it("refuses sections, unbalanced braces and EMAIL fields on SMS", () => {
    expect(templateProblems("SMS", { body: "{{#a}}x{{/a}} {{b", subject: "s", senderId: "too-long-sender" })).toEqual([
      "body: placeholder {{#a}} is not a plain {{name}}",
      "body: placeholder {{/a}} is not a plain {{name}}",
      "body: unbalanced {{ or }}",
      "subject is for EMAIL only",
      "senderId must be 1-11 letters, digits or spaces",
    ]);
  });

  it("EMAIL needs a subject", () => {
    expect(templateProblems("EMAIL", { body: "x" })).toEqual(["subject is required for EMAIL"]);
    expect(templateProblems("EMAIL", { body: " " , subject: "s" })).toEqual(["body is required"]);
  });
});

describe("template keys and SMS size", () => {
  it("parses code / channel / locale as the contract does", () => {
    expect(parseTemplateKey("TXN_P2P_SENT", "SMS", "bn")).toEqual({ code: "TXN_P2P_SENT", channel: "SMS", locale: "bn" });
    expect(parseTemplateKey("txn", "SMS", "bn")).toBeNull();
    expect(parseTemplateKey("TXN", "FAX", "bn")).toBeNull();
    expect(parseTemplateKey("TXN", "SMS", "fr")).toBeNull();
  });

  it("Bangla is UCS-2: 70 characters in one SMS, 67 per part beyond", () => {
    expect(smsInfo("Hello")).toEqual({ encoding: "GSM-7", length: 5, parts: 1 });
    expect(smsInfo("ক".repeat(70))).toEqual({ encoding: "UCS-2", length: 70, parts: 1 });
    expect(smsInfo("ক".repeat(71))).toEqual({ encoding: "UCS-2", length: 71, parts: 2 });
    expect(smsInfo("a".repeat(161))).toEqual({ encoding: "GSM-7", length: 161, parts: 2 });
  });
});
