"use server";
// Web checkout (contracts checkout.v1): the dev merchant "Demo Shop" (APISIX hmac-auth consumer
// merchant_demo, wallet 01911000001) creates a session; the payer pays on the hosted page API with
// the OTP the SMSC simulator received and the PIN; the merchant checks the signed redirect and
// the signed IPN the simulator's merchant endpoint received. The merchant secret stays here.

import { checkoutHeaders, queryOf, verifyCheckoutResult, verifyIpn } from "@/lib/crypto";
import { normaliseMsisdn, takaToMinor } from "@/lib/format";
import type { Outcome } from "@/lib/types";
import { Trace, poll } from "@/lib/server/edge";
import { checkoutSecret } from "@/lib/server/env";
import { core, freshCodes, codes } from "@/lib/server/test-apis";

type Co = { sessionId?: string; token?: string; orderId?: string; amountMinor?: number; otp?: string; wallet?: string };
export type CheckoutState = (Outcome & { data: Co & Record<string, unknown> }) | null;

const str = (f: FormData, k: string) => String(f.get(k) ?? "").trim();
const IPN_URL = "http://mw-simulators:8080/merchant/ipn/tester";
const taka = (m: number) => `${Math.floor(m / 100)}.${String(m % 100).padStart(2, "0")}`;

export async function checkout(prev: CheckoutState, form: FormData): Promise<CheckoutState> {
  const t = new Trace();
  const op = str(form, "op");
  const co: Co = { ...(prev?.data ?? {}) };
  const out = (headline: string, extra: Record<string, unknown> = {}): CheckoutState => ({ ok: !t.failed, headline, steps: t.steps, data: { ...co, ...extra } });
  try {
    switch (op) {
      case "create": {
        const amount = takaToMinor(str(form, "amount"));
        if (!amount) return out("enter an amount in taka");
        const orderId = `TC-${crypto.randomUUID().slice(0, 8)}`;
        const body = JSON.stringify({
          orderId, amount: { valueMinor: amount, currency: "BDT" }, successUrl: "https://shop.example.test/checkout/ok?cart=7",
          failUrl: "https://shop.example.test/checkout/fail", cancelUrl: "https://shop.example.test/checkout/cancel", ipnUrl: IPN_URL,
          description: `test console order ${orderId}`, locale: str(form, "locale") || "en",
        });
        const path = "/checkout/v1/sessions";
        const r = await t.call("merchant: create a checkout session (APISIX hmac-auth: Date, Digest, Signature)", "POST", path,
          { headers: checkoutHeaders("POST", path, body, checkoutSecret(), "merchant-demo", new Date()), raw: body }, [201]);
        const b = (r.body ?? {}) as { sessionId?: string; checkoutUrl?: string; status?: string };
        Object.assign(co, { sessionId: b.sessionId, orderId, amountMinor: amount, otp: undefined,
          token: b.checkoutUrl ? b.checkoutUrl.slice(b.checkoutUrl.lastIndexOf("/") + 1) : undefined });
        return out(r.status === 201 ? `session ${b.sessionId} ${b.status}: the shop sends the buyer to ${b.checkoutUrl}` : "the session was not created");
      }
      case "page": {
        if (!co.token) return out("create a session first");
        const r = await t.call("payer: open the hosted checkout page (the token in the URL is the only credential)", "GET", `/checkout/pay/v1/${co.token}`);
        const b = (r.body ?? {}) as { merchantName?: string; amount?: { valueMinor?: number }; status?: string };
        return out(`page shows ${b.merchantName ?? "?"} Tk ${taka(b.amount?.valueMinor ?? 0)} (${b.status ?? r.status})`);
      }
      case "otp": {
        if (!co.token || !co.amountMinor) return out("create a session first");
        const wallet = normaliseMsisdn(str(form, "wallet"));
        if (!wallet) return out("enter the payer's wallet number");
        const re = new RegExp(`Your Bits code is ([0-9]{6}) to pay Tk ${taka(co.amountMinor).replace(".", "\\.")} to `);
        const before = await codes(wallet, re);
        await t.call("payer: ask for the OTP to the wallet", "POST", `/checkout/pay/v1/${co.token}/otp`, { json: { wallet } }, [202]);
        const fresh = await poll(() => freshCodes(wallet, re, before), (l) => l.length > 0, 30000);
        co.wallet = wallet;
        co.otp = fresh[0];
        t.note("read the OTP SMS from the SMSC simulator", co.otp ? `OTP ${co.otp}` : "no OTP SMS within 30 s", !!co.otp);
        return out(co.otp ? `OTP ${co.otp} sent to ${wallet}` : "no OTP arrived");
      }
      case "confirm": {
        if (!co.token) return out("create a session first");
        const otp = str(form, "otp") || co.otp || "";
        const pin = str(form, "pin");
        if (!/^\d{6}$/.test(otp) || !/^\d{4,6}$/.test(pin)) return out("the OTP is 6 digits, the PIN 4 to 6");
        const before = co.wallet ? await core.wallet(co.wallet) : null;
        const r = await t.call("payer: confirm with OTP and the encrypted PIN", "POST", `/checkout/pay/v1/${co.token}/confirm`,
          { json: { otp, encryptedPin: await core.encrypt(pin) } }, [200]);
        const after = co.wallet ? await core.wallet(co.wallet) : null;
        const b = (r.body ?? {}) as { status?: string; txnId?: string };
        if (before && after) t.note("payer balance", `${taka(before.balanceMinor)} -> ${taka(after.balanceMinor)}`);
        return out(`result ${b.status ?? r.status}${b.txnId ? ` (txn ${b.txnId})` : ""}`, { txnId: b.txnId });
      }
      case "return": {
        if (!co.token) return out("create a session first");
        const r = await t.call("browser: back to the shop (303 to the signed success / fail URL)", "GET", `/checkout/pay/v1/${co.token}/return`, {}, [303]);
        const loc = r.headers.get("location") ?? "";
        const q = queryOf(loc);
        const valid = verifyCheckoutResult(q, checkoutSecret());
        t.note("merchant: verify the signed result", valid ? `signature valid, status ${q.status}` : "signature INVALID", valid, q);
        return out(`redirect to ${loc.split("?")[0]} with status ${q.status ?? "?"}`);
      }
      case "merchant": {
        if (!co.sessionId) return out("create a session first");
        const path = `/checkout/v1/sessions/${co.sessionId}`;
        await t.call("merchant: read the session (hmac-auth)", "GET", path, { headers: checkoutHeaders("GET", path, null, checkoutSecret(), "merchant-demo", new Date()) });
        const r = await t.call("IPNs the merchant's server received (simulator)", "GET", `/test/sim/merchant/ipn/received?ipnId=${co.sessionId}`, { apikey: true });
        const list = Array.isArray(r.body) ? (r.body as { headers?: Record<string, string>; body?: string; answeredStatus?: number }[]) : [];
        const checks = list.map((i) => ({ answered: i.answeredStatus, attempt: i.headers?.["x-bits-attempt"], signatureValid: verifyIpn(i.headers ?? {}, i.body ?? "", checkoutSecret()), body: i.body }));
        t.note("merchant: verify each IPN signature", `${list.length} IPN(s)`, checks.every((c) => c.signatureValid), checks);
        return out(`${list.length} IPN(s) received, answered ${checks.map((c) => c.answered).join(", ") || "-"}`);
      }
      default:
        return out("unknown step");
    }
  } catch (e) {
    t.note("stopped", (e as Error).message, false);
    return out((e as Error).message);
  }
}
