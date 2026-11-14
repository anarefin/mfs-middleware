"use client";
// Wallet authentication at mw-auth (ticket 76): every step on its own wallet, never a seeded one.

import { KeyRound, Lock, RotateCcw, ShieldCheck, Smartphone, UserPlus } from "lucide-react";
import { useActionState, useState } from "react";
import { Busy, EmptyResult, Hint, Panel, Row, TwoColumns } from "@/components/section";
import { Button } from "@/components/ui/button";
import { Field } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { authFlow } from "../actions/auth";
import { Result } from "../components/result";

export function AuthTester() {
  const [state, run, pending] = useActionState(authFlow, null);
  const made = state?.data?.msisdn ? String(state.data.msisdn) : "";
  const [msisdn, setMsisdn] = useState("");
  const wallet = msisdn || made;
  const hidden = <input type="hidden" name="msisdn" value={wallet} />;
  return (
    <TwoColumns>
      <div className="flex min-w-0 flex-col gap-5">
        <Panel title="1. A fresh wallet" icon={UserPlus}>
          <form action={run} className="flex flex-col gap-3">
            <Field label="First PIN (5 digits; 11111, 12345 or digits of the number are refused as weak)">
              <Input name="pin" type="password" defaultValue="48207" autoComplete="off" data-testid="auth-first-pin" />
            </Field>
            <Button type="submit" name="op" value="fresh" disabled={pending} data-testid="auth-fresh"><UserPlus />Create a fresh wallet</Button>
          </form>
          <Field label="Wallet (made here)">
            <Input value={wallet} onChange={(e) => setMsisdn(e.target.value)} className="font-mono" data-testid="auth-msisdn" />
          </Field>
          <Hint>
            One of core-mock&apos;s customers 01711000500..01711000999 without an app PIN: a new device is bound, the LOGIN OTP signs in
            and the first PIN is set. The seeded users (PIN 13579) are never touched here.
          </Hint>
        </Panel>

        <Panel title="2. Log in, second device" icon={Smartphone}>
          <form action={run} className="flex flex-col gap-3">
            {hidden}
            <Field label="PIN"><Input name="pin" type="password" defaultValue="48207" autoComplete="off" data-testid="auth-pin" /></Field>
            <Row>
              <Button type="submit" name="op" value="login" variant="outline" disabled={pending || !wallet} data-testid="auth-login"><KeyRound />Log in with the PIN</Button>
              <Button type="submit" name="op" value="second" variant="outline" disabled={pending || !wallet} data-testid="auth-second"><Smartphone />Bind a second device</Button>
            </Row>
          </form>
          <Hint>A second device takes the binding: the old one can no longer log in or refresh. Its transactions carry newDevice for 24 h (shown in the step).</Hint>
        </Panel>

        <Panel title="3. Change or reset the PIN" icon={RotateCcw}>
          <form action={run} className="flex flex-col gap-3">
            {hidden}
            <Row>
              <Field label="Current PIN"><Input name="pin" type="password" defaultValue="48207" autoComplete="off" data-testid="auth-current-pin" /></Field>
              <Field label="New PIN"><Input name="newPin" type="password" defaultValue="61938" autoComplete="off" data-testid="auth-new-pin" /></Field>
            </Row>
            <Row>
              <Field label="NID last 4"><Input name="nidLast4" defaultValue="4821" className="font-mono" /></Field>
              <Field label="Date of birth"><Input name="dob" defaultValue="1990-05-17" className="font-mono" /></Field>
            </Row>
            <Row>
              <Button type="submit" name="op" value="change" variant="outline" disabled={pending || !wallet} data-testid="auth-change">Change PIN</Button>
              <Button type="submit" name="op" value="selfReset" variant="outline" disabled={pending || !wallet} data-testid="auth-self-reset">Self-service reset</Button>
              <Button type="submit" name="op" value="careTakeUp" variant="outline" disabled={pending || !wallet} data-testid="auth-care">Take up a care reset (new phone)</Button>
            </Row>
          </form>
          <Hint>
            Care reset: in the ops console an ops-maker asks PIN_RESET for the wallet (Wallets) and an ops-checker approves it; then take it
            up here on a new phone (reset OTP, new PIN, then the phone is bound with it).
          </Hint>
        </Panel>

        <Panel title="4. Lockout" icon={Lock}>
          <form action={run}>
            {hidden}
            <Button type="submit" name="op" value="wrongPins" variant="outline" className="w-full" disabled={pending || !wallet} data-testid="auth-wrong">
              <Lock />Three wrong PINs (MW_AUTH_LOCK_TRIES)
            </Button>
          </form>
          <Hint>The third locks the PIN for MW_AUTH_LOCK_LEVEL1 (1 min with the dev timers); the right PIN is then not even checked. An approved ops unlock lifts it at once.</Hint>
        </Panel>
      </div>
      <div className="flex min-w-0 flex-col gap-5">
        {pending ? <Busy>Working… each step appears when the call returns.</Busy> : null}
        {state ? <Panel title="mw-auth" icon={ShieldCheck}><Result state={state} /></Panel> : <EmptyResult>Create a fresh wallet first; every call shows here.</EmptyResult>}
      </div>
    </TwoColumns>
  );
}
