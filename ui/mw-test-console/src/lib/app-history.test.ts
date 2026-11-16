import { describe, expect, it } from "vitest";
import { appendPage, historyQuery, parseHistory } from "./app-history";

describe("historyQuery", () => {
  it("builds the query with a fixed page size", () => {
    expect(historyQuery({})).toEqual({ ok: true, query: "limit=20&offset=0", offset: 0 });
    expect(historyQuery({ txnType: "p2p", direction: "in", offset: "40" })).toEqual({ ok: true, query: "limit=20&offset=40&txnType=P2P&direction=IN", offset: 40 });
  });

  it("refuses bad filters", () => {
    expect(historyQuery({ direction: "SIDEWAYS" }).ok).toBe(false);
    expect(historyQuery({ txnType: "P2P; DROP" }).ok).toBe(false);
    expect(historyQuery({ offset: "-1" }).ok).toBe(false);
    expect(historyQuery({ offset: "10001" }).ok).toBe(false);
  });
});

describe("parseHistory", () => {
  const row = { txnId: "11111111-1111-4111-8111-111111111111", txnType: "P2P", direction: "OUT", status: "COMPLETED",
    amount: { valueMinor: 1000, currency: "BDT" }, fee: { valueMinor: 500, currency: "BDT" },
    counterparty: { type: "WALLET", id: "0171****002", walletType: "CUSTOMER" }, requestedAt: "2026-11-16T10:00:00Z", updatedAt: "2026-11-16T10:00:01Z" };

  it("reads a page and skips malformed items", () => {
    const p = parseHistory({ items: [row, { txnId: "x" }, "junk"], limit: 20, offset: 0, hasMore: true });
    expect(p?.items).toHaveLength(1);
    expect(p?.items[0]).toMatchObject({ direction: "OUT", fee: { valueMinor: 500 }, counterparty: { id: "0171****002" } });
    expect(p?.hasMore).toBe(true);
  });

  it("is null for an error envelope", () => {
    expect(parseHistory({ code: "MW-SYS-013", message: "history unavailable", retryable: true })).toBeNull();
  });

  it("appends a later page without repeating a row", () => {
    const a = parseHistory({ items: [row], limit: 20, offset: 0, hasMore: true })!.items;
    const b = parseHistory({ items: [row, { ...row, txnId: "22222222-2222-4222-8222-222222222222" }], limit: 20, offset: 20, hasMore: false })!.items;
    expect(appendPage(a, b).map((i) => i.txnId)).toEqual([row.txnId, "22222222-2222-4222-8222-222222222222"]);
  });
});
