"use client";
// The mobile app: pick who is signed in, bind the device, run a flow, see every step.

import { ArrowLeftRight, Loader2, RotateCcw, Search, Send, Smartphone, UserRound } from "lucide-react";
import { useActionState, useState } from "react";
import { Busy, EmptyResult, Hint, Panel, Row, TwoColumns } from "@/components/section";
import { Button } from "@/components/ui/button";
import { Field } from "@/components/ui/field";
import { Input, NativeSelect } from "@/components/ui/input";
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

  const SELECT_OPS = ["DESCO", "DPDC", "WASA", "TITAS"];
  return (
    <TwoColumns>
      <div className="flex min-w-0 flex-col gap-5">
        <Panel title="1. Who is signed in" icon={UserRound}>
          <Field label="User (customers realm)">
            <Input list="app-users" value={user} onChange={(e) => setUser(e.target.value)} name="user-visible" data-testid="app-user" />
            <datalist id="app-users">
              {APP_PERSONAS.map((p) => <option key={p.user} value={p.user}>{p.label}, {p.msisdn}</option>)}
            </datalist>
          </Field>
          <form action={bind} className="flex flex-wrap gap-2">
            <input type="hidden" name="user" value={user} />
            <Button type="submit" variant="outline" disabled={busy} data-testid="bind"><Smartphone />{binding ? "binding…" : "Bind device"}</Button>
            <Button type="submit" variant="outline" name="force" value="1" disabled={busy}>Re-bind (new device)</Button>
          </form>
          <Hint>The first submit binds automatically; binding here shows the OTP steps on their own.</Hint>
        </Panel>

        <Panel title="2. Flow" icon={ArrowLeftRight}>
          <Field label="Transaction type">
            <NativeSelect value={type} onChange={(e) => pick(e.target.value as TxnType)} data-testid="txn-type">
              {TXN_TYPES.map((t) => <option key={t} value={t}>{FLOW_HELP[t].title}</option>)}
            </NativeSelect>
          </Field>
          <p className="m-0 rounded-md bg-muted px-3 py-2 text-sm text-muted-foreground">{help.hint}</p>
          <form action={submit} className="flex flex-col gap-3" key={type}>
            <input type="hidden" name="user" value={user} />
            <input type="hidden" name="txnType" value={type} />
            {type === "TOPUP" ? (
              <Row>
                <Field label="Operator"><NativeSelect name="operator" defaultValue="gp">{["gp", "robi", "banglalink", "teletalk"].map((o) => <option key={o}>{o}</option>)}</NativeSelect></Field>
                <Field label="Product"><NativeSelect name="product" defaultValue="PREPAID"><option>PREPAID</option><option>POSTPAID</option></NativeSelect></Field>
              </Row>
            ) : null}
            {type === "BILL_PAY" ? (
              <Field label="Biller"><NativeSelect name="biller" defaultValue="DESCO">{SELECT_OPS.map((o) => <option key={o}>{o}</option>)}</NativeSelect></Field>
            ) : null}
            {type === "ADD_MONEY_BANK" ? <input type="hidden" name="bank" value="c2wbank" /> : null}
            <Field label={type === "TOPUP" ? "Number to top up" : type === "BILL_PAY" ? "Bill number" : type === "ADD_MONEY_BANK" ? "Bank account (debited)" : "Counterparty wallet"}>
              <Input name="counterparty" defaultValue={help.counterparty} data-testid="counterparty" className="font-mono" />
            </Field>
            <Row>
              <Field label="Amount (Tk)"><Input name="amount" defaultValue={DEFAULT_AMOUNT[type]} data-testid="amount" className="tabular-nums" /></Field>
              <Field label="PIN"><Input name="pin" type="password" defaultValue="1234" autoComplete="off" data-testid="pin" /></Field>
            </Row>
            <Field label="Idempotency-Key (empty = new)"><Input name="idempotencyKey" placeholder="tc-…" className="font-mono" /></Field>
            <Button type="submit" disabled={busy} data-testid="submit">
              {submitting ? <Loader2 className="animate-spin" /> : <Send />}
              {submitting ? "running… (up to a minute with a timeout)" : "Submit"}
            </Button>
          </form>
          <form action={resend}>
            <input type="hidden" name="user" value={user} />
            <Button type="submit" variant="outline" className="w-full" disabled={busy} data-testid="resend"><RotateCcw />{resending ? "resending…" : "Resend the last submit (same Idempotency-Key)"}</Button>
          </form>
        </Panel>

        <Panel title="3. Look things up" icon={Search}>
          <form action={read} className="flex flex-col gap-3">
            <input type="hidden" name="user" value={user} />
            <Row>
              <Button type="submit" variant="outline" name="what" value="billers" disabled={busy}>Billers</Button>
              <Button type="submit" variant="outline" name="what" value="collections" disabled={busy}>Merchant collections</Button>
            </Row>
            <Row>
              <Field label="Biller"><NativeSelect name="biller" defaultValue="DESCO">{SELECT_OPS.map((o) => <option key={o}>{o}</option>)}</NativeSelect></Field>
              <Field label="Bill number"><Input name="counterparty" defaultValue="1234567" className="font-mono" /></Field>
              <Button type="submit" variant="outline" name="what" value="fetchBill" disabled={busy}>Fetch bill</Button>
            </Row>
            <Row>
              <Field label="txnId"><Input name="txnId" defaultValue={txnState?.txnId ?? ""} key={txnState?.txnId ?? "none"} className="font-mono" /></Field>
              <Button type="submit" variant="outline" name="what" value="status" disabled={busy}>Status</Button>
            </Row>
          </form>
        </Panel>
      </div>
      <div className="flex min-w-0 flex-col gap-5">
        {busy ? <Busy>Working… each step appears when the call returns.</Busy> : null}
        {[txnState, resendState, bindState, readState].filter(Boolean).length === 0 ? (
          <EmptyResult>Run something on the left: the result, balances, SMS and every API call show here.</EmptyResult>
        ) : null}
        {txnState ? <Panel title="Submit" icon={Send}><Result state={txnState} /></Panel> : null}
        {resendState ? <Panel title="Resend" icon={RotateCcw}><Result state={resendState} /></Panel> : null}
        {bindState ? <Panel title="Device" icon={Smartphone}><Result state={bindState} /></Panel> : null}
        {readState ? <Panel title="Lookup" icon={Search}><Result state={readState} /></Panel> : null}
      </div>
    </TwoColumns>
  );
}
