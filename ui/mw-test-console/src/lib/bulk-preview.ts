// Reads mw-portal-api's bulk upload answer and GET .../{id}/preview page (ticket 129b) for the
// /bulk page. Pure, read defensively: any field may be missing.
import { formatMinor } from "./format";

type Amount = { valueMinor?: number; currency?: string };
type Row = { line?: number; msisdn?: string; amount?: Amount; reference?: string; valid?: boolean; errors?: string[] };
export type BatchAnswer = {
  batchId?: string;
  status?: string;
  rowCount?: number;
  validCount?: number;
  invalidCount?: number;
  totalAmount?: Amount;
  rows?: Row[];
};

export function previewHeadline(b: BatchAnswer | null | undefined): string {
  const head = `batch ${b?.batchId ?? "?"}: ${b?.status ?? "?"}`;
  if (typeof b?.rowCount !== "number" || typeof b.validCount !== "number" || typeof b.invalidCount !== "number") return head;
  const total = typeof b.totalAmount?.valueMinor === "number" ? `, ${formatMinor(b.totalAmount.valueMinor)} to pay` : "";
  return `${head}, ${b.rowCount} rows (${b.validCount} valid, ${b.invalidCount} invalid)${total}`;
}

export function previewRows(b: BatchAnswer | null | undefined): string[] {
  return (b?.rows ?? []).map((r) => {
    const ref = r.reference ? ` ${r.reference}` : "";
    if (r.valid === false) return `line ${r.line}: invalid (${(r.errors ?? []).join(", ")})${ref}`;
    const amount = typeof r.amount?.valueMinor === "number" ? ` ${formatMinor(r.amount.valueMinor)}` : "";
    return `line ${r.line}: ${r.msisdn ?? ""}${amount}${ref}`;
  });
}
