"use client";
// The mobile app: pick who is signed in, bind the device, run a flow, see every step.

import { useActionState, useState } from "react";
import { APP_PERSONAS, FLOW_HELP, TXN_TYPES, type TxnType } from "@/lib/personas";
import { appRead, bindDevice, resendLast, submitTxn } from "../actions/app";
import { Result } from "../components/result";

const DEFAULT_USER: Record<TxnType, string> = {
  P2P: "customer01", CASH_IN: "agent01", CASH_OUT: "customer01", MERCHANT_PAY: "customer01",
  TOPUP: "customer01", BILL_PAY: "customer01", ADD_MONEY_BANK: "customer01",
};
const DEFAULT_AMOUNT: Record<TxnType, string> = {
  P2P: "10", CASH_IN: "20", CASH_OUT: "20", MERCHANT_PAY: "25", TOPUP: "20", BILL_PAY: "100", ADD_MONEY_BANK: "500",
};

export function AppTester() {
  const [type, setType] = useState<TxnType>("P2P");
  const [user, setUser] = useState("customer01");
  const [bindState, bind, binding] = useActionState(bindDevice, null);
  const [txnState, submit, submitting] = useActionState(submitTxn, null);
  const [resendState, resend, resending] = useActionState(resendLast, null);
  const [readState, read, reading] = useActionState(appRead, null);
  const help = FLOW_HELP[type];
  const busy = binding || submitting || resending || reading;
  const pick = (t: TxnType) => {
    setType(t);
    setUser(DEFAULT_USER[t]);
  };

  return (
    <div className="cols">
      <div>
        <section className="panel">
          <h2>1. Who is signed in</h2>
          <label>User (customers realm)
            <input list="app-users" value={user} onChange={(e) => setUser(e.target.value)} name="user-visible" data-testid="app-user" />
            <datalist id="app-users">
              {APP_PERSONAS.map((p) => <option key={p.user} value={p.user}>{p.label}, {p.msisdn}</option>)}
            </datalist>
          </label>
          <form action={bind} className="row" style={{ marginTop: 8 }}>
            <input type="hidden" name="user" value={user} />
            <button type="submit" disabled={busy} data-testid="bind">{binding ? "binding…" : "Bind device"}</button>
            <button type="submit" name="force" value="1" disabled={busy}>Re-bind (new device)</button>
          </form>
          <p className="hint">The first submit binds automatically; binding here shows the OTP steps on their own.</p>
        </section>

        <section className="panel">
          <h2>2. Flow</h2>
          <label>Transaction type
            <select value={type} onChange={(e) => pick(e.target.value as TxnType)} data-testid="txn-type">
              {TXN_TYPES.map((t) => <option key={t} value={t}>{FLOW_HELP[t].title}</option>)}
            </select>
          </label>
          <p className="help">{help.hint}</p>
          <form action={submit} className="stack" key={type}>
            <input type="hidden" name="user" value={user} />
            <input type="hidden" name="txnType" value={type} />
            {type === "TOPUP" ? (
              <div className="row">
                <label>Operator<select name="operator" defaultValue="gp">{["gp", "robi", "banglalink", "teletalk"].map((o) => <option key={o}>{o}</option>)}</select></label>
                <label>Product<select name="product" defaultValue="PREPAID"><option>PREPAID</option><option>POSTPAID</option></select></label>
              </div>
            ) : null}
            {type === "BILL_PAY" ? (
              <label>Biller<select name="biller" defaultValue="DESCO">{["DESCO", "DPDC", "WASA", "TITAS"].map((o) => <option key={o}>{o}</option>)}</select></label>
            ) : null}
            {type === "ADD_MONEY_BANK" ? <input type="hidden" name="bank" value="c2wbank" /> : null}
            <label>
              {type === "TOPUP" ? "Number to top up" : type === "BILL_PAY" ? "Bill number" : type === "ADD_MONEY_BANK" ? "Bank account (debited)" : "Counterparty wallet"}
              <input name="counterparty" defaultValue={help.counterparty} data-testid="counterparty" />
            </label>
            <div className="row">
              <label>Amount (Tk)<input name="amount" defaultValue={DEFAULT_AMOUNT[type]} data-testid="amount" /></label>
              <label>PIN<input name="pin" type="password" defaultValue="1234" autoComplete="off" data-testid="pin" /></label>
            </div>
            <label>Idempotency-Key (empty = new)<input name="idempotencyKey" placeholder="tc-…" /></label>
            <button type="submit" className="primary" disabled={busy} data-testid="submit">{submitting ? "running… (up to a minute with a timeout)" : "Submit"}</button>
          </form>
          <form action={resend} style={{ marginTop: 8 }}>
            <input type="hidden" name="user" value={user} />
            <button type="submit" disabled={busy} data-testid="resend">{resending ? "resending…" : "Resend the last submit (same Idempotency-Key)"}</button>
          </form>
        </section>

        <section className="panel">
          <h2>3. Look things up</h2>
          <form action={read} className="stack">
            <input type="hidden" name="user" value={user} />
            <div className="row">
              <button type="submit" name="what" value="billers" disabled={busy}>Billers</button>
              <button type="submit" name="what" value="collections" disabled={busy}>Merchant collections</button>
            </div>
            <div className="row">
              <label>Biller<select name="biller" defaultValue="DESCO">{["DESCO", "DPDC", "WASA", "TITAS"].map((o) => <option key={o}>{o}</option>)}</select></label>
              <label>Bill number<input name="counterparty" defaultValue="1234567" /></label>
              <button type="submit" name="what" value="fetchBill" disabled={busy}>Fetch bill</button>
            </div>
            <div className="row">
              <label>txnId<input name="txnId" defaultValue={txnState?.txnId ?? ""} key={txnState?.txnId ?? "none"} /></label>
              <button type="submit" name="what" value="status" disabled={busy}>Status</button>
            </div>
          </form>
        </section>
      </div>
      <div>
        {busy ? <p className="busy">Working… each step appears when the call returns.</p> : null}
        {[txnState, resendState, bindState, readState].filter(Boolean).length === 0 ? (
          <section className="panel"><p className="hint">Run something on the left: the result, balances, SMS and every API call show here.</p></section>
        ) : null}
        {txnState ? <section className="panel"><h2>Submit</h2><Result state={txnState} /></section> : null}
        {resendState ? <section className="panel"><h2>Resend</h2><Result state={resendState} /></section> : null}
        {bindState ? <section className="panel"><h2>Device</h2><Result state={bindState} /></section> : null}
        {readState ? <section className="panel"><h2>Lookup</h2><Result state={readState} /></section> : null}
      </div>
    </div>
  );
}
