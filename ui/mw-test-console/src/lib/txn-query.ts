// The transaction explorer's search as mw-admin-api takes it (GET /admin/v1/transactions, contract
// admin.v1): checked here first so a typo never costs an ops token. Pure, no I/O.

/** The saga states (mw-admin-api TransactionController.TxnStatus). */
export const TXN_STATUSES = [
  "RECEIVED", "VALIDATING", "HOLDING", "POSTING", "PARTNER_PENDING", "UNKNOWN", "COMMITTING", "RELEASING", "REVERSING",
  "COMPLETED", "FAILED", "REJECTED", "REVERSED", "MANUAL_REVIEW",
] as const;

/** Types offered as suggestions (any [A-Z0-9_]{1,32} may be typed). */
export const TXN_TYPE_SUGGESTIONS = ["P2P", "CASH_IN", "CASH_OUT", "MERCHANT_PAY", "TOPUP", "BILL_PAY", "ADD_MONEY_BANK", "BULK_DISBURSE", "REVERSAL"];

export const PAGE_SIZE = 50;

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MSISDN = /^\+?[0-9 -]{8,20}$/;
const TYPE = /^[A-Z0-9_]{1,32}$/;

export const isTxnId = (s: string): boolean => UUID.test(s);

export type TxnSearch = { txnId?: string; msisdn?: string; status?: string; txnType?: string; limit: number; offset: number };

export type ParsedSearch = { ok: true; search: TxnSearch; query: string } | { ok: false; error: string };

const str = (v: unknown): string => (typeof v === "string" ? v.trim() : "");

/** The form's fields (txnId, msisdn, status, txnType, offset) as the admin-api query string. */
export function parseTxnSearch(raw: Record<string, unknown>): ParsedSearch {
  const txnId = str(raw.txnId).toLowerCase();
  const msisdn = str(raw.msisdn);
  const status = str(raw.status).toUpperCase();
  const txnType = str(raw.txnType).toUpperCase();
  const offsetText = str(raw.offset) || "0";
  if (!txnId && !msisdn && !status && !txnType) return { ok: false, error: "enter a txnId or an MSISDN (optionally a status or a type)" };
  if (txnId && !isTxnId(txnId)) return { ok: false, error: "the txnId must be a UUID" };
  if (msisdn && !MSISDN.test(msisdn)) return { ok: false, error: "the MSISDN must be a phone number (01XXXXXXXXX)" };
  if (status && !(TXN_STATUSES as readonly string[]).includes(status)) return { ok: false, error: `unknown status ${status}` };
  if (txnType && !TYPE.test(txnType)) return { ok: false, error: "the type is upper-case letters, digits and _ only (e.g. P2P, TOPUP)" };
  if (!/^\d{1,6}$/.test(offsetText) || Number(offsetText) > 100_000) return { ok: false, error: "the offset is 0 to 100000" };
  const search: TxnSearch = {
    ...(txnId ? { txnId } : {}), ...(msisdn ? { msisdn } : {}), ...(status ? { status } : {}), ...(txnType ? { txnType } : {}),
    limit: PAGE_SIZE, offset: Number(offsetText),
  };
  const q = new URLSearchParams();
  for (const k of ["txnId", "msisdn", "status", "txnType"] as const) if (search[k]) q.set(k, search[k]);
  q.set("limit", String(search.limit));
  q.set("offset", String(search.offset));
  return { ok: true, search, query: q.toString() };
}

/** ?txnId= of the explorer page, when it is a UUID. */
export function initialTxnId(v: string | string[] | undefined): string | undefined {
  const s = (Array.isArray(v) ? v[0] : v)?.trim();
  return s && isTxnId(s) ? s.toLowerCase() : undefined;
}

/** The explorer link of a transaction (next/link adds the /tester basePath). */
export const txnHref = (txnId: string): string => `/transactions?txnId=${encodeURIComponent(txnId)}`;

// mw-admin-api's answers (AdminTxn, TxnDetailView, AuditTrail): party ids arrive masked.
export type Amount = { valueMinor: number; currency: string };
export type Party = { type?: string; id?: string; name?: string; walletType?: string };
export type AdminTxn = {
  txnId: string; txnType: string; channel: string; status: string; previousStatus?: string;
  createdAt: string; updatedAt: string; correlationId?: string; amount: Amount; fee?: Amount;
  initiator?: Party; debitParty?: Party; creditParty?: Party;
  partner?: { partnerId?: string; productCode?: string; billerCode?: string };
  partnerRef?: string; coreRef?: string; originalTxnId?: string; reversalTxnId?: string;
  error?: { code?: string; message?: string; messageBn?: string; retryable?: boolean };
  attributes?: Record<string, string>;
};
export type TxnPage = { items: AdminTxn[]; limit: number; offset: number };
export type TimelineEntry = {
  kind: "STATE" | "CORE"; from?: string; to?: string; trigger?: string; command?: string; commandId?: string;
  sends?: number; result?: string; outcome?: string; reasonCode?: string; at: string; resultAt?: string;
};
export type PartnerCall = {
  requestId?: string; operation?: string; family?: string; partnerId?: string; sentAt?: string;
  outcome?: string; partnerRef?: string; errorCode?: string; answeredAt?: string;
};
export type AuditEntry = {
  auditId?: string; seq?: number; recordedAt?: string; at?: string; txnId?: string; correlationId?: string;
  actor?: { type?: string; id?: string }; action?: string; resource?: string; outcome?: string; service?: string;
  before?: Record<string, unknown>; after?: Record<string, unknown>;
};
export type TxnDetail = {
  transaction: AdminTxn; timeline: TimelineEntry[]; partnerCalls: PartnerCall[]; auditTrail: AuditEntry[];
  auditAnchor?: { status?: string; lastAnchorDate?: string; verifiedAt?: string }; revealed: boolean;
};
