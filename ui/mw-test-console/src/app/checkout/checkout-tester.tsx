"use client";
// Web checkout in the order a real one runs: the shop creates the session, the buyer opens the
// hosted page, gets an OTP, confirms with OTP + PIN, is sent back to the shop; the shop's server
// receives the IPN. One form, a button per step; the session carries over between steps.

import { CreditCard, ListChecks, Store, Undo2 } from "lucide-react";
import { useActionState } from "react";
import { Busy, EmptyResult, Hint, Panel, Row, TwoColumns } from "@/components/section";
import { Button } from "@/components/ui/button";
import { Field } from "@/components/ui/field";
import { Input, NativeSelect } from "@/components/ui/input";
import { checkout } from "../actions/checkout";
import { Result } from "../components/result";

export function CheckoutTester() {
  const [state, run, pending] = useActionState(checkout, null);
  const d = state?.data;
  return (
    <TwoColumns>
      <form action={run} className="flex min-w-0 flex-col gap-5" aria-busy={pending}>
        <Panel title="1. Shop (merchant Demo Shop)" icon={Store}>
          <Row>
            <Field label="Amount (Tk)"><Input name="amount" defaultValue="25" data-testid="co-amount" className="tabular-nums" /></Field>
            <Field label="Page language"><NativeSelect name="locale" defaultValue="en"><option value="en">en</option><option value="bn">bn</option></NativeSelect></Field>
          </Row>
          <Button type="submit" name="op" value="create" disabled={pending} data-testid="co-create">Create checkout session</Button>
          {d?.sessionId ? <Hint>session <code className="font-mono">{String(d.sessionId)}</code> · order <code className="font-mono">{String(d.orderId)}</code></Hint> : null}
        </Panel>
        <Panel title="2. Buyer on the hosted page" icon={CreditCard}>
          <Button type="submit" name="op" value="page" variant="outline" disabled={pending || !d?.token}>Open the checkout page</Button>
          <Row>
            <Field label="Payer wallet"><Input name="wallet" defaultValue="01711000001" data-testid="co-wallet" className="font-mono" /></Field>
            <Button type="submit" name="op" value="otp" variant="outline" disabled={pending || !d?.token} data-testid="co-otp">Send OTP</Button>
          </Row>
          <Row>
            <Field label="OTP (filled from the SMS)"><Input name="otp" defaultValue={d?.otp ? String(d.otp) : ""} key={String(d?.otp ?? "")} data-testid="co-otp-value" className="font-mono" /></Field>
            <Field label="PIN"><Input name="pin" type="password" defaultValue="1234" autoComplete="off" /></Field>
          </Row>
          <Button type="submit" name="op" value="confirm" disabled={pending || !d?.token} data-testid="co-confirm">Pay</Button>
          <Hint>Try a wrong OTP (MW-AUTH-010, nothing paid) or Pay twice (the second is refused).</Hint>
        </Panel>
        <Panel title="3. Back at the shop" icon={Undo2}>
          <Button type="submit" name="op" value="return" variant="outline" disabled={pending || !d?.token} data-testid="co-return">Return to the shop (signed redirect)</Button>
          <Button type="submit" name="op" value="merchant" variant="outline" disabled={pending || !d?.sessionId} data-testid="co-ipn">Shop server: session status and IPNs</Button>
          <Hint>IPN retries: Simulators → preset &quot;Merchant IPN endpoint fails the next 2&quot; before paying.</Hint>
        </Panel>
      </form>
      <div className="flex min-w-0 flex-col gap-5">
        {pending ? <Busy>working…</Busy> : null}
        {state ? <Panel title="Result" icon={ListChecks}><Result state={state} /></Panel> : <EmptyResult>Start with step 1.</EmptyResult>}
      </div>
    </TwoColumns>
  );
}
