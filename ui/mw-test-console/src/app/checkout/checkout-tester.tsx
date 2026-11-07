"use client";
// Web checkout in the order a real one runs: the shop creates the session, the buyer opens the
// hosted page, gets an OTP, confirms with OTP + PIN, is sent back to the shop; the shop's server
// receives the IPN. One form, a button per step; the session carries over between steps.

import { useActionState } from "react";
import { checkout } from "../actions/checkout";
import { Result } from "../components/result";

export function CheckoutTester() {
  const [state, run, pending] = useActionState(checkout, null);
  const d = state?.data;
  return (
    <div className="cols">
      <form action={run} className="stack" aria-busy={pending}>
        <section className="panel stack">
          <h2>1. Shop (merchant Demo Shop)</h2>
          <div className="row">
            <label>Amount (Tk)<input name="amount" defaultValue="25" data-testid="co-amount" /></label>
            <label>Page language<select name="locale" defaultValue="en"><option value="en">en</option><option value="bn">bn</option></select></label>
          </div>
          <button type="submit" name="op" value="create" className="primary" disabled={pending} data-testid="co-create">Create checkout session</button>
          {d?.sessionId ? <p className="hint">session <code>{String(d.sessionId)}</code> · order <code>{String(d.orderId)}</code></p> : null}
        </section>
        <section className="panel stack">
          <h2>2. Buyer on the hosted page</h2>
          <button type="submit" name="op" value="page" disabled={pending || !d?.token}>Open the checkout page</button>
          <div className="row">
            <label>Payer wallet<input name="wallet" defaultValue="01711000001" data-testid="co-wallet" /></label>
            <button type="submit" name="op" value="otp" disabled={pending || !d?.token} data-testid="co-otp">Send OTP</button>
          </div>
          <div className="row">
            <label>OTP (filled from the SMS)<input name="otp" defaultValue={d?.otp ? String(d.otp) : ""} key={String(d?.otp ?? "")} data-testid="co-otp-value" /></label>
            <label>PIN<input name="pin" type="password" defaultValue="1234" autoComplete="off" /></label>
          </div>
          <button type="submit" name="op" value="confirm" className="primary" disabled={pending || !d?.token} data-testid="co-confirm">Pay</button>
          <p className="hint">Try a wrong OTP (MW-AUTH-010, nothing paid) or Pay twice (the second is refused).</p>
        </section>
        <section className="panel stack">
          <h2>3. Back at the shop</h2>
          <button type="submit" name="op" value="return" disabled={pending || !d?.token} data-testid="co-return">Return to the shop (signed redirect)</button>
          <button type="submit" name="op" value="merchant" disabled={pending || !d?.sessionId} data-testid="co-ipn">Shop server: session status and IPNs</button>
          <p className="hint">IPN retries: Simulators → preset &quot;Merchant IPN endpoint fails the next 2&quot; before paying.</p>
        </section>
      </form>
      <div>
        {pending ? <p className="busy">working…</p> : null}
        {state ? <section className="panel"><Result state={state} /></section> : <section className="panel"><p className="hint">Start with step 1.</p></section>}
      </div>
    </div>
  );
}
