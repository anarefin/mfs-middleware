import { describe, expect, it } from "vitest";
import { initialTxnId, isTxnId, parseTxnSearch, txnHref } from "./txn-query";

const ID = "0f8fad5b-d9cb-469f-a165-70867728950e";

describe("isTxnId", () => {
  it("takes a UUID in any case, nothing else", () => {
    expect(isTxnId(ID)).toBe(true);
    expect(isTxnId(ID.toUpperCase())).toBe(true);
    expect(isTxnId("abc")).toBe(false);
    expect(isTxnId(`${ID}x`)).toBe(false);
    expect(isTxnId("")).toBe(false);
  });
});

describe("parseTxnSearch", () => {
  it("needs at least one filter", () => {
    expect(parseTxnSearch({})).toEqual({ ok: false, error: expect.stringContaining("txnId or an MSISDN") });
    expect(parseTxnSearch({ txnId: "  ", msisdn: "" }).ok).toBe(false);
  });

  it("builds the admin-api query of a txnId, lower-cased and trimmed", () => {
    const r = parseTxnSearch({ txnId: `  ${ID.toUpperCase()} ` });
    expect(r).toEqual({ ok: true, search: { txnId: ID, limit: 50, offset: 0 }, query: `txnId=${ID}&limit=50&offset=0` });
  });

  it("refuses a txnId that is not a UUID", () => {
    expect(parseTxnSearch({ txnId: "abc" })).toEqual({ ok: false, error: "the txnId must be a UUID" });
  });

  it("takes an MSISDN as admin-api does (digits, spaces, dashes, a leading +), with status and type", () => {
    const r = parseTxnSearch({ msisdn: "01711000001", status: "COMPLETED", txnType: "p2p", offset: "50" });
    expect(r.ok).toBe(true);
    if (r.ok) expect(r.query).toBe("msisdn=01711000001&status=COMPLETED&txnType=P2P&limit=50&offset=50");
    expect(parseTxnSearch({ msisdn: "+880 1711-000001" }).ok).toBe(true);
    expect(parseTxnSearch({ msisdn: "12ab" })).toEqual({ ok: false, error: "the MSISDN must be a phone number (01XXXXXXXXX)" });
  });

  it("refuses an unknown status, a bad type and a bad offset", () => {
    expect(parseTxnSearch({ msisdn: "01711000001", status: "DONE" })).toEqual({ ok: false, error: "unknown status DONE" });
    expect(parseTxnSearch({ msisdn: "01711000001", txnType: "top-up" })).toEqual({ ok: false, error: "the type is upper-case letters, digits and _ only (e.g. P2P, TOPUP)" });
    expect(parseTxnSearch({ msisdn: "01711000001", offset: "-1" }).ok).toBe(false);
  });

  it("a status or a type alone is a search", () => {
    expect(parseTxnSearch({ status: "MANUAL_REVIEW" }).ok).toBe(true);
    expect(parseTxnSearch({ txnType: "TOPUP" }).ok).toBe(true);
  });

  it("ignores FormData's non-string values", () => {
    expect(parseTxnSearch({ txnId: ID, msisdn: new Blob() as unknown as string }).ok).toBe(true);
  });
});

describe("initialTxnId and txnHref", () => {
  it("reads ?txnId= when it is a UUID", () => {
    expect(initialTxnId(ID)).toBe(ID);
    expect(initialTxnId([ID, "x"])).toBe(ID);
    expect(initialTxnId("nope")).toBeUndefined();
    expect(initialTxnId(undefined)).toBeUndefined();
  });

  it("links into the explorer (next/link adds the /tester basePath)", () => {
    expect(txnHref(ID)).toBe(`/transactions?txnId=${ID}`);
  });
});
