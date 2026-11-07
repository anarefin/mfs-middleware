"use server";
// The mobile app, played through the app API (/app/v1, contracts app.v1): bind the device, submit
// a money flow (signed, PIN encrypted with the core key), follow it to its final status, compare
// the core-mock balances before and after, and collect the SMS the wallets received.

import { takaToMinor, normaliseMsisdn } from "@/lib/format";
import { TXN_TYPES, type TxnType } from "@/lib/personas";
import { FINAL, type BalanceRow, type Outcome, type SmsRow } from "@/lib/types";
import { deviceHeaders, deviceState, ready, signedHeaders } from "@/lib/server/device";
import { EdgeError, Trace, edge, poll, sleep, type EdgeResponse } from "@/lib/server/edge";
import { publicUrl } from "@/lib/server/env";
import { core, sim } from "@/lib/server/test-apis";
import { walletOf } from "@/lib/server/tokens";

export type AppState = Outcome | null;

const str = (f: FormData, k: string) => String(f.get(k) ?? "").trim();
const USER = /^[a-z0-9][a-z0-9._-]{1,63}$/;

function fail(t: Trace, headline: string): Outcome {
  return { ok: false, headline, steps: t.steps };
}

async function guarded(t: Trace, fn: () => Promise<Outcome>): Promise<Outcome> {
  try {
    return await fn();
  } catch (e) {
    t.note("stopped", (e as Error).message, false);
    return fail(t, (e as Error).message);
  }
}

type LastSubmit = { idem: string; body: string; user: string; notes: string[] };
const g = globalThis as unknown as { __tcLast?: Map<string, LastSubmit> };
const lastSubmit = (g.__tcLast ??= new Map());

/** Bind (or re-bind with force) the user's device: OTP by SMS, then the signed binding. */
export async function bindDevice(_prev: AppState, form: FormData): Promise<AppState> {
  const t = new Trace();
  const user = str(form, "user");
  if (!USER.test(user)) return fail(t, "choose a user");
  return guarded(t, async () => {
    await ready(t, user, form.get("force") === "1");
    const s = deviceState(user);
    return { ok: !t.failed, headline: `device ${s.deviceId} bound for ${user} (${await walletOf(user)})`, steps: t.steps };
  });
}

function buildBody(type: TxnType, f: FormData, amount: number, encryptedPin: unknown): { body: Record<string, unknown>; credit?: string } | string {
  const cp = str(f, "counterparty");
  const base = { txnType: type, amount: { valueMinor: amount, currency: "BDT" }, encryptedPin };
  switch (type) {
    case "P2P": case "CASH_IN": case "CASH_OUT": case "MERCHANT_PAY": {
      const w = normaliseMsisdn(cp);
      if (!w) return "the counterparty must be a wallet number (01XXXXXXXXX)";
      return { body: { ...base, creditParty: { id: w } }, credit: w };
    }
    case "TOPUP": {
      const w = normaliseMsisdn(cp);
      if (!w) return "the number to top up must be a mobile number";
      return { body: { ...base, creditParty: { id: w, type: "MSISDN" }, partner: { partnerId: str(f, "operator") || "gp", productCode: str(f, "product") || "PREPAID" } } };
    }
    case "BILL_PAY":
      if (!/^\w{3,32}$/.test(cp)) return "enter the bill number";
      return { body: { ...base, creditParty: { id: cp, type: "BILLER_ACCOUNT" }, partner: { billerCode: str(f, "biller") || "DESCO" } } };
    case "ADD_MONEY_BANK":
      if (!/^\d{6,20}$/.test(cp)) return "the bank account number is 6 to 20 digits";
      return { body: { ...base, debitParty: { type: "BANK_ACCOUNT", id: cp }, partner: { partnerId: str(f, "bank") || "c2wbank" } } };
  }
}

async function balances(wallets: string[]): Promise<Map<string, { b: number | null; h: number | null }>> {
  const m = new Map<string, { b: number | null; h: number | null }>();
  for (const w of wallets) {
    const x = await core.wallet(w);
    m.set(w, { b: x?.balanceMinor ?? null, h: x?.heldMinor ?? null });
  }
  return m;
}

/** The c2wbank simulator answers 202 and reports by callback: make sure a callback is set. */
async function ensureBankCallback(t: Trace): Promise<void> {
  const cur = await edge("GET", "/test/sim/c2wbank/scenario", { apikey: true });
  const s = (cur.body ?? {}) as Record<string, unknown>;
  if (cur.status === 200 && s.callbackMode && s.callbackMode !== "NONE" && s.callbackUrl) return;
  await t.call("c2wbank had no callback set: set bank callbacks ONCE (Simulators page to change)", "POST", "/test/sim/c2wbank/scenario", {
    apikey: true, json: { ...s, callbackMode: "ONCE", callbackUrl: "http://apisix:9080/partner/c2wbank/callback/debits", callbackDelayMs: 0 },
  }, [200, 201, 202, 204]);
}

async function follow(t: Trace, user: string, r: EdgeResponse, body: string, idem: string, wallets: string[], started: number): Promise<Outcome> {
  const b = (r.body ?? {}) as { txnId?: string; status?: string; code?: string; message?: string };
  const txnId = b.txnId;
  // a core refusal is answered 422 with the error envelope (code MW-CORE-<reason>, txnId): REJECTED
  let status = b.status ?? (txnId && b.code ? "REJECTED" : undefined);
  if (txnId && status && !(FINAL as readonly string[]).includes(status)) {
    const last = await poll(async () => edge("GET", `/app/v1/transactions/${txnId}`, { headers: await deviceHeaders(user) }),
      (x) => (FINAL as readonly string[]).includes(String((x.body as { status?: string } | null)?.status)), 60000, 1000);
    t.steps.push({ label: `poll the transaction until it is final (was ${status})`, call: `GET /app/v1/transactions/${txnId}`, status: last.status,
      ok: last.status === 200, body: last.body, ms: last.ms });
    status = (last.body as { status?: string } | null)?.status ?? status;
  }
  lastSubmit.set(user, { idem, body, user, notes: wallets });
  const why = b.code ? ` ${b.code} ${b.message ?? ""}` : "";
  return { ok: !t.failed, headline: txnId ? `${txnId}: ${status}${why}` : `refused:${why || ` HTTP ${r.status}`}`, steps: t.steps, txnId, status,
    data: { idempotencyKey: idem, consoleUrl: txnId ? `${publicUrl()}/console/transactions/${txnId}` : undefined, started } };
}

async function finish(o: Outcome, before: Map<string, { b: number | null; h: number | null }>, wallets: string[], started: number): Promise<Outcome> {
  // the core applies a moment after the answer in some paths: give the balances time to settle
  await sleep(1200);
  const after = await balances(wallets);
  const rows: BalanceRow[] = wallets.map((w) => ({ wallet: w, beforeMinor: before.get(w)?.b ?? null, afterMinor: after.get(w)?.b ?? null, heldMinor: after.get(w)?.h ?? null }));
  const sms: SmsRow[] = [];
  for (const w of wallets) {
    for (const m of await sim.sms(w)) {
      const at = Date.parse(m.receivedAt ?? "");
      if (!Number.isNaN(at) && at >= started - 1000) sms.push(m);
    }
  }
  return { ...o, balances: rows, sms };
}

/** Submit one money flow from the app: signed POST /app/v1/transactions, then follow it. */
export async function submitTxn(_prev: AppState, form: FormData): Promise<AppState> {
  const t = new Trace();
  const user = str(form, "user");
  const type = str(form, "txnType") as TxnType;
  const amount = takaToMinor(str(form, "amount"));
  const pin = str(form, "pin");
  if (!USER.test(user)) return fail(t, "choose a user");
  if (!(TXN_TYPES as readonly string[]).includes(type)) return fail(t, "choose a flow");
  if (!amount) return fail(t, "the amount is taka with at most 2 decimals, e.g. 10 or 20.13");
  if (!/^\d{4,6}$/.test(pin)) return fail(t, "the PIN is 4 to 6 digits (seeded wallets: 1234)");
  const started = Date.now();
  return guarded(t, async () => {
    const payer = await walletOf(user);
    const encryptedPin = await core.encrypt(pin);
    t.note("encrypt the PIN on the device", "RSA-OAEP SHA-256 with the core's public key: the PIN never travels in clear");
    const built = buildBody(type, form, amount, encryptedPin);
    if (typeof built === "string") return fail(t, built);
    // FEE-POOL: core-mock's fee account (P2P Tk 5, top-up Tk 2, …: the payer pays amount + fee)
    const wallets = [payer, ...(built.credit && built.credit !== payer ? [built.credit] : []), "FEE-POOL"];
    const before = await balances(wallets);
    if (type === "ADD_MONEY_BANK") await ensureBankCallback(t);
    await ready(t, user);
    const json = JSON.stringify(built.body);
    const idem = str(form, "idempotencyKey") || `tc-${crypto.randomUUID()}`;
    const r = await t.call(`submit ${type} (signed, Idempotency-Key ${idem})`, "POST", "/app/v1/transactions",
      { headers: { ...(await signedHeaders(user, "POST", "/app/v1/transactions", json)), "Idempotency-Key": idem }, raw: json }, [200, 201, 202]);
    return finish(await follow(t, user, r, json, idem, wallets, started), before, wallets, started);
  });
}

/** Send the user's last submit again, byte for byte with the same Idempotency-Key (new signature). */
export async function resendLast(_prev: AppState, form: FormData): Promise<AppState> {
  const t = new Trace();
  const user = str(form, "user");
  const last = lastSubmit.get(user);
  if (!last) return fail(t, `no earlier submit of ${user} in this console (submit one first)`);
  const started = Date.now();
  return guarded(t, async () => {
    const before = await balances(last.notes);
    await ready(t, user);
    const r = await t.call(`resend the same body with the same Idempotency-Key ${last.idem}`, "POST", "/app/v1/transactions",
      { headers: { ...(await signedHeaders(user, "POST", "/app/v1/transactions", last.body)), "Idempotency-Key": last.idem }, raw: last.body }, [200, 201, 202]);
    t.note("what to check", "the same txnId comes back and no balance moves a second time");
    return finish(await follow(t, user, r, last.body, last.idem, last.notes, started), before, last.notes, started);
  });
}

/** Read-only app calls: billers, fetch a bill, a transaction's status, the merchant's collections. */
export async function appRead(_prev: AppState, form: FormData): Promise<AppState> {
  const t = new Trace();
  const user = str(form, "user");
  const what = str(form, "what");
  if (!USER.test(user)) return fail(t, "choose a user");
  return guarded(t, async () => {
    await ready(t, user);
    const h = await deviceHeaders(user);
    switch (what) {
      case "billers":
        await t.call("list the billers", "GET", "/app/v1/billers", { headers: h });
        break;
      case "fetchBill": {
        const biller = str(form, "biller") || "DESCO";
        const bill = str(form, "counterparty");
        await t.call(`fetch bill ${bill} of ${biller}`, "POST", `/app/v1/billers/${encodeURIComponent(biller)}/fetch-bill`, { headers: h, json: { billNumber: bill } });
        break;
      }
      case "status": {
        const id = str(form, "txnId");
        if (!/^[0-9a-f-]{36}$/i.test(id)) throw new EdgeError("enter a txnId");
        await t.call("the transaction as the app sees it", "GET", `/app/v1/transactions/${id}`, { headers: h });
        break;
      }
      case "collections":
        await t.call("the merchant's collections (sign in as merchant01)", "GET", "/app/v1/merchant/collections", { headers: h });
        break;
      default:
        throw new EdgeError("unknown read");
    }
    const last = t.steps[t.steps.length - 1];
    return { ok: !t.failed, headline: `${last?.call} -> HTTP ${last?.status}`, steps: t.steps };
  });
}
