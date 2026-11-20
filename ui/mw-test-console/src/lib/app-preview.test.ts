import { describe, expect, it } from "vitest";
import { TXN_TYPES } from "./personas";
import { confirmBody, confirmPath, previewBody, previewPath, quoteLines, shownQuote } from "./app-preview";

const p2p = {
  txnType: "P2P",
  creditParty: { id: "01711000002" },
  amount: { valueMinor: 1000, currency: "BDT" },
  encryptedPin: { keyId: "core-pin-1", ciphertext: "Y2lwaGVy" },
  locale: "en",
};

describe("preview and confirm bodies", () => {
  it("maps every flow onto its preview and confirm path", () => {
    expect(TXN_TYPES.map((t) => previewPath(t))).toEqual([
      "/app/v1/send-money/preview",
      "/app/v1/cash-in/preview",
      "/app/v1/cash-out/preview",
      "/app/v1/payment/preview",
      "/app/v1/top-up/preview",
      "/app/v1/bill-pay/preview",
      "/app/v1/add-money/preview",
    ]);
    expect(confirmPath("P2P")).toBe("/app/v1/send-money/confirm");
    expect(confirmPath("ADD_MONEY_BANK")).toBe("/app/v1/add-money/confirm");
  });

  it("preview drops the type and the PIN, confirm binds the preview id and keeps an edited amount", () => {
    expect(previewBody(p2p)).toEqual({
      creditParty: { id: "01711000002" },
      amount: { valueMinor: 1000, currency: "BDT" },
      locale: "en",
    });
    const edited = { ...p2p, amount: { valueMinor: 1001, currency: "BDT" } };
    expect(confirmBody("prev-1", edited)).toEqual({
      previewId: "prev-1",
      creditParty: { id: "01711000002" },
      amount: { valueMinor: 1001, currency: "BDT" },
      locale: "en",
      encryptedPin: { keyId: "core-pin-1", ciphertext: "Y2lwaGVy" },
    });
    expect(confirmBody("prev-1", edited)).not.toHaveProperty("txnType");
  });
});

describe("quoteLines", () => {
  it("shows the core charges, the masked payee and the limits", () => {
    expect(quoteLines({
      previewId: "prev-1",
      fee: { valueMinor: 500, currency: "BDT" },
      vat: { valueMinor: 65, currency: "BDT" },
      commission: { valueMinor: 0, currency: "BDT" },
      total: { valueMinor: 1500, currency: "BDT" },
      counterparty: { id: "0171****002", name: "A." },
      limits: { remaining: { daily: { valueMinor: 1_500_000 }, dailyCount: 49 } },
    })).toEqual([
      { label: "Fee", value: "Tk 5.00" },
      { label: "VAT", value: "Tk 0.65" },
      { label: "Commission", value: "Tk 0.00" },
      { label: "Total", value: "Tk 15.00" },
      { label: "Name", value: "A." },
      { label: "Counterparty", value: "0171****002" },
      { label: "Limits", value: "daily Tk 15,000.00, 49 left today" },
    ]);
  });

  it("a refusal, or a preview without an id, shows no quote", () => {
    expect(shownQuote(false, { previewId: "prev-1", fee: { valueMinor: 500 } })).toBeNull();
    expect(shownQuote(true, { fee: { valueMinor: 500 } })).toBeNull();
    expect(shownQuote(true, { previewId: "prev-1" })?.previewId).toBe("prev-1");
  });
});
