// The SP portal API (/portal/sp/v1, ticket 129d) as the /sp page calls it. Pure. The org is the
// token's: no path here ever carries one.

export type SpOp = "txns" | "txn" | "report";
export type SpFilters = { status?: string; from?: string; to?: string; txnId?: string };

const B = "/portal/sp/v1";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const WEEK_MS = 7 * 24 * 3600 * 1000;

export function spPath(op: SpOp, f: SpFilters, now: Date = new Date()): string {
  const q = new URLSearchParams();
  switch (op) {
    case "txn": {
      const id = (f.txnId ?? "").trim();
      if (!UUID.test(id)) throw new Error("enter a transaction id (a UUID from the list)");
      return `${B}/transactions/${id}`;
    }
    case "txns":
      if (f.status) q.set("status", f.status);
      if (f.from) q.set("from", f.from);
      if (f.to) q.set("to", f.to);
      q.set("limit", "50");
      return `${B}/transactions?${q}`;
    case "report":
      q.set("from", f.from || new Date(now.getTime() - WEEK_MS).toISOString());
      q.set("to", f.to || now.toISOString());
      if (f.status) q.set("status", f.status);
      return `${B}/reports?${q}`;
  }
}

/** How many transactions a CSV report holds (lines under the header). */
export function reportSummary(csv: unknown): string {
  if (typeof csv !== "string" || csv === "") return "no report";
  const lines = csv.split("\n").filter((l) => l !== "").length - 1;
  return `${Math.max(lines, 0)} transaction(s) in the report`;
}
