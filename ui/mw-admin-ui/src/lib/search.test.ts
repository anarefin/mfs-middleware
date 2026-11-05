import { describe, expect, it } from "vitest";
import { parseSearch } from "./search";

describe("parseSearch", () => {
  it("asks for nothing until the operator gives a filter", () => {
    const r = parseSearch({});
    expect(r.submitted).toBe(false);
    expect(r.errors).toEqual([]);
  });

  it("passes a txnId through as the admin API filter", () => {
    const r = parseSearch({ txnId: " 3f1c2b9e-7a4d-4c1e-9b8a-2d6f0e5a1c33 " });
    expect(r.errors).toEqual([]);
    expect(r.submitted).toBe(true);
    expect(r.query.toString()).toBe("txnId=3f1c2b9e-7a4d-4c1e-9b8a-2d6f0e5a1c33&limit=50&offset=0");
  });

  it("refuses a txnId that is not a UUID", () => {
    const r = parseSearch({ txnId: "abc" });
    expect(r.errors).toEqual([{ field: "txnId", issue: "must be a UUID" }]);
  });

  it("accepts an MSISDN with spaces or a +880 prefix, as the admin API does", () => {
    expect(parseSearch({ msisdn: "+880 1712-345678" }).query.get("msisdn")).toBe("+880 1712-345678");
    expect(parseSearch({ msisdn: "01712345678" }).errors).toEqual([]);
    expect(parseSearch({ msisdn: "0171abc" }).errors).toEqual([{ field: "msisdn", issue: "must be a phone number" }]);
  });

  it("turns the date inputs into the [from, to) window in UTC", () => {
    const r = parseSearch({ from: "2026-11-01", to: "2026-11-02" });
    expect(r.query.get("from")).toBe("2026-11-01T00:00:00.000Z");
    expect(r.query.get("to")).toBe("2026-11-03T00:00:00.000Z");
  });

  it("checks status against the saga states and keeps paging in bounds", () => {
    expect(parseSearch({ status: "COMPLETED" }).query.get("status")).toBe("COMPLETED");
    expect(parseSearch({ status: "DONE" }).errors).toEqual([{ field: "status", issue: "unknown status" }]);
    const r = parseSearch({ status: "FAILED", offset: "100", limit: "999" });
    expect(r.query.get("limit")).toBe("200");
    expect(r.query.get("offset")).toBe("100");
  });

  it("ignores empty fields and takes the first value of a repeated one", () => {
    const r = parseSearch({ txnId: "", partnerRef: ["P-1", "P-2"] });
    expect(r.query.get("txnId")).toBeNull();
    expect(r.query.get("partnerRef")).toBe("P-1");
  });
});
