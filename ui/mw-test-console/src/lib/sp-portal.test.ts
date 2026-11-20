import { describe, expect, it } from "vitest";
import { reportSummary, spPath } from "./sp-portal";

describe("SP portal paths", () => {
  it("never sends an org: only the filters the SP API takes", () => {
    expect(spPath("txns", { status: "COMPLETED", from: "", to: "" })).toBe("/portal/sp/v1/transactions?status=COMPLETED&limit=50");
    expect(spPath("txns", { status: "", from: "2026-11-01T00:00:00Z", to: "" })).toBe(
      "/portal/sp/v1/transactions?from=2026-11-01T00%3A00%3A00Z&limit=50",
    );
    expect(spPath("report", { status: "", from: "2026-11-01T00:00:00Z", to: "2026-11-08T00:00:00Z" })).toBe(
      "/portal/sp/v1/reports?from=2026-11-01T00%3A00%3A00Z&to=2026-11-08T00%3A00%3A00Z",
    );
  });

  it("refuses a transaction id that is not a UUID", () => {
    expect(spPath("txn", { txnId: "019a5b2e-1c00-7abc-8def-000000000002" })).toBe(
      "/portal/sp/v1/transactions/019a5b2e-1c00-7abc-8def-000000000002",
    );
    expect(() => spPath("txn", { txnId: "../bulk" })).toThrow(/transaction id/);
  });

  it("defaults the report to the last 7 days", () => {
    const now = new Date("2026-11-20T10:15:00Z");
    expect(spPath("report", { from: "", to: "" }, now)).toBe(
      "/portal/sp/v1/reports?from=2026-11-13T10%3A15%3A00.000Z&to=2026-11-20T10%3A15%3A00.000Z",
    );
  });
});

describe("reportSummary", () => {
  it("counts the lines under the header", () => {
    expect(reportSummary("txnId,createdAt\na,b\nc,d\n")).toBe("2 transaction(s) in the report");
    expect(reportSummary("txnId,createdAt\n")).toBe("0 transaction(s) in the report");
    expect(reportSummary(undefined)).toBe("no report");
  });
});
