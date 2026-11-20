"use server";
// Inbound remittance (contracts remit-inbound.v1, tickets 119b/119c): the console plays the partner
// UCB IMT. Every request goes through APISIX (route partner-remit-v1) to mw-adapter-remit, signed with
// the partner's HMAC secret. Lookup first (masked name and number), then send; the credit is paid from
// SETTLEMENT_UCB_IMT and the partner's status query shows CREDITED or REJECTED.

import { partnerHeaders } from "@/lib/crypto";
import { normaliseMsisdn, takaToMinor } from "@/lib/format";
import type { Outcome } from "@/lib/types";
import { Trace, poll } from "@/lib/server/edge";
import { ucbImtSecret } from "@/lib/server/env";
import { core } from "@/lib/server/test-apis";

export type RemitState = (Outcome & { data: { partnerRef?: string; upayRef?: string } }) | null;

const PARTNER = "ucb-imt";
const SETTLEMENT = "SETTLEMENT_UCB_IMT";
const str = (f: FormData, k: string) => String(f.get(k) ?? "").trim();

function signed(t: Trace, label: string, method: "GET" | "POST", path: string, json?: unknown) {
  const body = json === undefined ? "" : JSON.stringify(json);
  const headers = partnerHeaders(PARTNER, method, path, body, ucbImtSecret());
  return t.call(label, method, path, json === undefined ? { headers } : { headers, raw: body }, method === "POST" && path.endsWith("/credits") ? [200, 202] : [200]);
}

export async function remit(prev: RemitState, form: FormData): Promise<RemitState> {
  const t = new Trace();
  const op = str(form, "op");
  const data = { ...(prev?.data ?? {}) };
  const out = (headline: string, extra: Partial<Outcome> = {}): RemitState => ({ ok: !t.failed, headline, steps: t.steps, ...extra, data });
  const wallet = normaliseMsisdn(str(form, "beneficiary"));
  if (!wallet) return out("enter the beneficiary's wallet number");
  try {
    if (op === "lookup") {
      const r = await signed(t, "partner ucb-imt: look the beneficiary up (masked)", "POST", "/remit/v1/beneficiary-lookup", { beneficiaryMsisdn: wallet });
      const b = (r.body ?? {}) as { maskedName?: string; maskedMsisdn?: string; eligible?: boolean; code?: string };
      return out(r.status === 200 ? `${b.maskedName ?? "(no name)"} · ${b.maskedMsisdn} · ${b.eligible ? "can receive" : "cannot receive now"}` : `lookup refused: ${b.code ?? r.status}`);
    }
    const amount = takaToMinor(str(form, "amount"));
    if (!amount) return out("enter an amount in taka");
    const before = await Promise.all([core.wallet(wallet), core.wallet(SETTLEMENT)]);
    const partnerRef = `TC-REMIT-${crypto.randomUUID().slice(0, 8)}`;
    const major = `${Math.floor(amount / 100)}.${String(amount % 100).padStart(2, "0")}`;
    const r = await signed(t, "partner ucb-imt: push the credit", "POST", "/remit/v1/credits", {
      partnerRef, beneficiaryMsisdn: wallet, amount: major, currency: "BDT",
      senderName: str(form, "sender") || "Test Sender", senderCountry: str(form, "country") || "AE",
    });
    const ack = (r.body ?? {}) as { upayRef?: string; status?: string; code?: string };
    Object.assign(data, { partnerRef, upayRef: ack.upayRef });
    if (r.status !== 202 && r.status !== 200) return out(`credit refused: ${ack.code ?? r.status}`);
    const path = `/remit/v1/credits/${encodeURIComponent(partnerRef)}`;
    const done = await poll(() => signed(t, "partner ucb-imt: status query", "GET", path), (x) => {
      const s = (x.body as { status?: string } | null)?.status;
      return !!s && s !== "ACCEPTED";
    }, 30000, 1000);
    const status = (done.body as { status?: string } | null)?.status ?? "?";
    const after = await Promise.all([core.wallet(wallet), core.wallet(SETTLEMENT)]);
    const balances = [wallet, SETTLEMENT].map((w, i) => ({
      wallet: w, beforeMinor: before[i]?.balanceMinor ?? null, afterMinor: after[i]?.balanceMinor ?? null, heldMinor: after[i]?.heldMinor ?? null,
    }));
    return out(`remittance ${partnerRef} Tk ${major}: ${status}`, { txnId: ack.upayRef, status, balances });
  } catch (e) {
    t.note("error", (e as Error).message, false);
    return out((e as Error).message);
  }
}
