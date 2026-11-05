// Transaction search form -> admin API query (GET /admin/v1/transactions, contract
// api/mw-admin-api/admin.v1). The form is a plain GET form, so its values arrive as the page's
// searchParams; this checks them the way the API does and builds the query string.

export const STATUSES = [
  "RECEIVED", "VALIDATING", "HOLDING", "POSTING", "PARTNER_PENDING", "COMMITTING", "RELEASING",
  "UNKNOWN", "COMPLETED", "REJECTED", "FAILED", "REVERSING", "REVERSED", "MANUAL_REVIEW",
] as const;

export type RawParams = Record<string, string | string[] | undefined>;
export type FieldError = { field: string; issue: string };
export type SearchForm = {
  txnId: string; msisdn: string; partnerRef: string; status: string; txnType: string;
  from: string; to: string;
};
export type ParsedSearch = {
  form: SearchForm;
  query: URLSearchParams;
  errors: FieldError[];
  /** true when at least one filter was given: an empty form does not query the API */
  submitted: boolean;
  limit: number;
  offset: number;
};

export const PAGE_SIZE = 50;
const MAX_LIMIT = 200;
const MAX_OFFSET = 100_000;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MSISDN = /^\+?[0-9 -]{8,20}$/;
const TXN_TYPE = /^[A-Z0-9_]{1,32}$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

function first(v: string | string[] | undefined): string {
  return (Array.isArray(v) ? v[0] : v)?.trim() ?? "";
}

function int(v: string, fallback: number, min: number, max: number): number {
  const n = Number.parseInt(v, 10);
  if (!Number.isFinite(n)) return fallback;
  return Math.min(Math.max(n, min), max);
}

/** Start of the given UTC day, plus `days`; null when the date is not a real yyyy-mm-dd. */
function utcDay(v: string, days: number): string | null {
  if (!DATE.test(v)) return null;
  const d = new Date(`${v}T00:00:00.000Z`);
  if (Number.isNaN(d.getTime()) || d.toISOString().slice(0, 10) !== v) return null;
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString();
}

export function parseSearch(params: RawParams): ParsedSearch {
  const form: SearchForm = {
    txnId: first(params.txnId), msisdn: first(params.msisdn), partnerRef: first(params.partnerRef),
    status: first(params.status), txnType: first(params.txnType), from: first(params.from),
    to: first(params.to),
  };
  const errors: FieldError[] = [];
  const query = new URLSearchParams();

  if (form.txnId) {
    if (UUID.test(form.txnId)) query.set("txnId", form.txnId.toLowerCase());
    else errors.push({ field: "txnId", issue: "must be a UUID" });
  }
  if (form.msisdn) {
    if (MSISDN.test(form.msisdn)) query.set("msisdn", form.msisdn);
    else errors.push({ field: "msisdn", issue: "must be a phone number" });
  }
  if (form.partnerRef) {
    if (form.partnerRef.length <= 128) query.set("partnerRef", form.partnerRef);
    else errors.push({ field: "partnerRef", issue: "at most 128 characters" });
  }
  if (form.status) {
    if ((STATUSES as readonly string[]).includes(form.status)) query.set("status", form.status);
    else errors.push({ field: "status", issue: "unknown status" });
  }
  if (form.txnType) {
    if (TXN_TYPE.test(form.txnType)) query.set("txnType", form.txnType);
    else errors.push({ field: "txnType", issue: "upper-case letters, digits and _ only" });
  }
  if (form.from) {
    const from = utcDay(form.from, 0);
    if (from) query.set("from", from);
    else errors.push({ field: "from", issue: "must be a date" });
  }
  if (form.to) {
    // the form's "to" day is inclusive; the API's `to` is an exclusive bound
    const to = utcDay(form.to, 1);
    if (to) query.set("to", to);
    else errors.push({ field: "to", issue: "must be a date" });
  }

  const submitted = Object.values(form).some((v) => v !== "");
  const limit = int(first(params.limit), PAGE_SIZE, 1, MAX_LIMIT);
  const offset = int(first(params.offset), 0, 0, MAX_OFFSET);
  query.set("limit", String(limit));
  query.set("offset", String(offset));
  return { form, query, errors, submitted, limit, offset };
}

/** The page's own URL for another page of the same search. */
export function pageHref(form: SearchForm, offset: number): string {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(form)) if (v) q.set(k, v);
  if (offset > 0) q.set("offset", String(offset));
  return `/transactions?${q.toString()}`;
}
