// The app's wallet history (GET /app/v1/transactions, app.v1 1.5.0; pure): the query the History
// panel sends and a defensive read of the page it gets back (an item of the wrong shape is skipped).

export const HISTORY_PAGE = 20;
export const DIRECTIONS = ["IN", "OUT"] as const;
export type Direction = (typeof DIRECTIONS)[number];
/** The types a wallet's history may hold: the app's flows and what reaches it from other channels. */
export const HISTORY_TYPES = ["P2P", "CASH_IN", "CASH_OUT", "MERCHANT_PAY", "TOPUP", "BILL_PAY", "ADD_MONEY_BANK", "BULK_DISBURSE"] as const;

export type HistoryItem = {
  txnId: string;
  txnType: string;
  direction: Direction;
  status: string;
  amount: { valueMinor: number; currency: string };
  fee?: { valueMinor: number; currency: string };
  counterparty: { type?: string; id?: string; name?: string; walletType?: string };
  coreRef?: string;
  error?: { code?: string; message?: string };
  requestedAt?: string;
  updatedAt?: string;
};
export type HistoryPage = { items: HistoryItem[]; limit: number; offset: number; hasMore: boolean };

export type HistoryQuery = { ok: true; query: string; offset: number } | { ok: false; error: string };

const s = (v: unknown) => String(v ?? "").trim();

/** The form's filters (txnType, direction, offset) as the query string; limit fixed at HISTORY_PAGE. */
export function historyQuery(raw: Record<string, unknown>): HistoryQuery {
  const txnType = s(raw.txnType).toUpperCase();
  const direction = s(raw.direction).toUpperCase();
  const offsetRaw = s(raw.offset) || "0";
  if (txnType && !/^[A-Z0-9_]{2,32}$/.test(txnType)) return { ok: false, error: "the type is upper-case letters, digits and _ only" };
  if (direction && !(DIRECTIONS as readonly string[]).includes(direction)) return { ok: false, error: "the direction is IN or OUT" };
  if (!/^\d{1,5}$/.test(offsetRaw) || Number(offsetRaw) > 10000) return { ok: false, error: "the offset is 0 to 10000" };
  const offset = Number(offsetRaw);
  const q = new URLSearchParams({ limit: String(HISTORY_PAGE), offset: String(offset) });
  if (txnType) q.set("txnType", txnType);
  if (direction) q.set("direction", direction);
  return { ok: true, query: q.toString(), offset };
}

type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => typeof v === "object" && v !== null && !Array.isArray(v);
const money = (v: unknown) => (isObj(v) && typeof v.valueMinor === "number" ? { valueMinor: v.valueMinor, currency: s(v.currency) || "BDT" } : undefined);
const opt = (v: unknown) => (typeof v === "string" && v !== "" ? v : undefined);

function item(o: Obj): HistoryItem | null {
  const txnId = opt(o.txnId);
  const amount = money(o.amount);
  const direction = o.direction === "IN" || o.direction === "OUT" ? o.direction : null;
  if (!txnId || !amount || !direction) return null;
  const cp = isObj(o.counterparty) ? o.counterparty : {};
  const err = isObj(o.error) ? { code: opt(o.error.code), message: opt(o.error.message) } : undefined;
  return {
    txnId, txnType: s(o.txnType), direction, status: s(o.status), amount, fee: money(o.fee),
    counterparty: { type: opt(cp.type), id: opt(cp.id), name: opt(cp.name), walletType: opt(cp.walletType) },
    coreRef: opt(o.coreRef), error: err, requestedAt: opt(o.requestedAt), updatedAt: opt(o.updatedAt),
  };
}

export function parseHistory(body: unknown): HistoryPage | null {
  if (!isObj(body) || !Array.isArray(body.items)) return null;
  const items = body.items.flatMap((x) => (isObj(x) ? [item(x)].filter((y): y is HistoryItem => y !== null) : []));
  return {
    items,
    limit: typeof body.limit === "number" ? body.limit : HISTORY_PAGE,
    offset: typeof body.offset === "number" ? body.offset : 0,
    hasMore: body.hasMore === true,
  };
}

/** A later page appended to the rows already shown (a row seen twice, as new ones arrive at the top, once). */
export function appendPage(shown: readonly HistoryItem[], next: readonly HistoryItem[]): HistoryItem[] {
  const seen = new Set(shown.map((i) => `${i.txnId}/${i.direction}`));
  return [...shown, ...next.filter((i) => !seen.has(`${i.txnId}/${i.direction}`))];
}
