// The app's two-step money flow (ticket 105, FRS CH-APP-05): preview, then confirm. Pure: the page
// and the server action both build the bodies here, and the quote box reads quoteLines.

import { formatMinor } from "@/lib/format";
import type { TxnType } from "@/lib/personas";

/** Path group of each flow (`POST /app/v1/{group}/preview` and `/confirm`). */
export const PREVIEW_GROUP: Record<TxnType, string> = {
  P2P: "send-money",
  CASH_IN: "cash-in",
  CASH_OUT: "cash-out",
  MERCHANT_PAY: "payment",
  TOPUP: "top-up",
  BILL_PAY: "bill-pay",
  ADD_MONEY_BANK: "add-money",
};

export const previewPath = (type: TxnType) => `/app/v1/${PREVIEW_GROUP[type]}/preview`;
export const confirmPath = (type: TxnType) => `/app/v1/${PREVIEW_GROUP[type]}/confirm`;

const DROPPED = new Set(["txnType", "encryptedPin", "confirmDuplicate", "previewId"]);

/** The preview body: the transaction without its type, PIN or duplicate confirmation. */
export function previewBody(txn: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(txn)) {
    if (value !== undefined && value !== null && !DROPPED.has(key)) out[key] = value;
  }
  return out;
}

/** The confirm body: the preview id, the transaction again (as it is now) and the PIN. */
export function confirmBody(previewId: string, txn: Record<string, unknown>): Record<string, unknown> {
  const pin = txn.encryptedPin;
  const duplicate = txn.confirmDuplicate;
  return {
    previewId,
    ...previewBody(txn),
    ...(pin !== undefined && pin !== null ? { encryptedPin: pin } : {}),
    ...(duplicate ? { confirmDuplicate: duplicate } : {}),
  };
}

export type Money = { valueMinor: number; currency?: string };

export type PreviewAnswer = {
  previewId?: string;
  expiresAt?: string;
  fee?: Money;
  vat?: Money;
  commission?: Money;
  total?: Money;
  counterparty?: { id?: string; name?: string | null };
  limits?: { remaining?: { daily?: Money; perTxn?: Money; monthly?: Money; dailyCount?: number; monthlyCount?: number } };
};

export type QuoteLine = { label: string; value: string };

const money = (m?: Money) => (typeof m?.valueMinor === "number" ? formatMinor(m.valueMinor) : "—");

/** What the preview step shows: fee, VAT, commission, total, the masked payee and the limits. */
export function quoteLines(answer: PreviewAnswer): QuoteLine[] {
  const lines: QuoteLine[] = [
    { label: "Fee", value: money(answer.fee) },
    { label: "VAT", value: money(answer.vat) },
    { label: "Commission", value: money(answer.commission) },
    { label: "Total", value: money(answer.total) },
  ];
  if (answer.counterparty?.name) lines.push({ label: "Name", value: answer.counterparty.name });
  if (answer.counterparty?.id) lines.push({ label: "Counterparty", value: answer.counterparty.id });
  const remaining = answer.limits?.remaining;
  if (remaining) {
    const parts: string[] = [];
    if (typeof remaining.daily?.valueMinor === "number") parts.push(`daily ${formatMinor(remaining.daily.valueMinor)}`);
    if (typeof remaining.dailyCount === "number") parts.push(`${remaining.dailyCount} left today`);
    lines.push({ label: "Limits", value: parts.length ? parts.join(", ") : "checked" });
  }
  return lines;
}

/** The quote to show after a preview that returned a previewId; a refusal shows no quote. */
export function shownQuote(ok: boolean, data: Record<string, unknown> | undefined): PreviewAnswer | null {
  if (!ok || !data || typeof data.previewId !== "string" || data.previewId === "") return null;
  return data as PreviewAnswer;
}
