"use client";
// The simulator, core-mock and SMPP control forms, each showing what its calls did.

import { Building2, Gauge, Radio, Wallet } from "lucide-react";
import { useActionState, type ReactNode } from "react";
import { Busy, Panel, Row, SubHeading } from "@/components/section";
import { Button } from "@/components/ui/button";
import { Field, InlineField } from "@/components/ui/field";
import { Input, NativeSelect } from "@/components/ui/input";
import type { Outcome } from "@/lib/types";
import { coreFault, readPartner, replayCallback, setScenario, smpp, walletLookup } from "../actions/sim";
import { Result } from "../components/result";

type Act = (prev: Outcome | null, f: FormData) => Promise<Outcome | null>;

export function ActForm({ action, children, testid }: { action: Act; children: ReactNode; testid?: string }) {
  const [state, run, pending] = useActionState(action, null);
  return (
    <form action={run} className="flex flex-col gap-3" aria-busy={pending} data-testid={testid}>
      <fieldset disabled={pending} className="contents">{children}</fieldset>
      {pending ? <Busy>working…</Busy> : null}
      <Result state={state} />
    </form>
  );
}

const PARTNER_INFO: Record<string, string> = {
  ocs: "OCS (prepaid top-up, every MNO)",
  billagg: "Bill aggregator (DESCO, DPDC, WASA, TITAS)",
  c2wbank: "C2W bank (add money from bank)",
};

const checkboxClass = "size-4 accent-primary";

export function PartnerForms() {
  return (
    <div className="grid items-start gap-4 xl:grid-cols-3">
      {Object.entries(PARTNER_INFO).map(([p, title]) => (
        <Panel key={p} title={title} icon={Building2} contentClassName="gap-4">
          <ActForm action={setScenario} testid={`scenario-${p}`}>
            <input type="hidden" name="partner" value={p} />
            <Row>
              <Field label="Outcome (forceMode)">
                <NativeSelect name="forceMode" defaultValue="">
                  <option value="">normal (magic values decide)</option>
                  <option value="SUCCESS">SUCCESS</option>
                  <option value="BUSINESS_FAILURE">BUSINESS_FAILURE</option>
                  <option value="TIMEOUT">TIMEOUT (applied, reply held)</option>
                  <option value="SERVER_ERROR_AFTER_SEND">500 after send (applied)</option>
                  <option value="UNKNOWN_CODE">unknown code 9876</option>
                  <option value="MALFORMED">malformed body (applied)</option>
                </NativeSelect>
              </Field>
              <Field label="Latency ms"><Input name="latencyMs" inputMode="numeric" placeholder="0" /></Field>
            </Row>
            <Row>
              <Field label="Error rate 0..1 (503 unapplied)"><Input name="errorRate" placeholder="0" /></Field>
              <Field label="Timeout hold ms"><Input name="timeoutDelayMs" inputMode="numeric" placeholder="default" /></Field>
            </Row>
            <InlineField><input type="checkbox" name="outage" className={checkboxClass} /> outage (every request 503, not applied)</InlineField>
            {p !== "ocs" ? (
              <>
                <Row>
                  <Field label="Callback">
                    <NativeSelect name="callbackMode" defaultValue={p === "c2wbank" ? "ONCE" : "NONE"}>
                      <option value="NONE">none</option>
                      <option value="ONCE">once</option>
                      <option value="DUPLICATE">twice (duplicate)</option>
                      <option value="LATE">late (after the delay)</option>
                      <option value="OUT_OF_ORDER">outcome then stale PENDING</option>
                    </NativeSelect>
                  </Field>
                  <Field label="Callback delay ms"><Input name="callbackDelayMs" inputMode="numeric" placeholder="0" /></Field>
                </Row>
                {p === "billagg" ? <InlineField><input type="checkbox" name="callbackOnly" className={checkboxClass} /> callback-only (payments answered 202 PENDING)</InlineField> : null}
              </>
            ) : null}
            <Row>
              <Button type="submit" name="op" value="set">Set {p}</Button>
              <Button type="submit" name="op" value="reset" variant="outline">Reset {p}</Button>
            </Row>
          </ActForm>
          <div className="flex flex-col gap-3 border-t pt-4">
            <SubHeading>What {p} received</SubHeading>
            <ActForm action={readPartner}>
              <input type="hidden" name="partner" value={p} />
              <Button type="submit" variant="outline" className="self-start">Show scenario and last 10 requests</Button>
            </ActForm>
          </div>
          {p !== "ocs" ? (
            <div className="flex flex-col gap-3 border-t pt-4">
              <SubHeading>Replay a callback</SubHeading>
              <ActForm action={replayCallback}>
                <input type="hidden" name="partner" value={p} />
                <Field label={`Partner reference (the transactionId ${p} received, see the requests)`}><Input name="reference" className="font-mono" /></Field>
                <Button type="submit" variant="outline" className="self-start">Replay</Button>
              </ActForm>
            </div>
          ) : null}
        </Panel>
      ))}
    </div>
  );
}

export function CoreForms() {
  return (
    <div className="grid items-start gap-4 xl:grid-cols-3">
      <Panel title="Inject a core-mock fault" icon={Gauge}>
        <ActForm action={coreFault} testid="core-fault">
          <Row>
            <Field label="Mode">
              <NativeSelect name="mode" defaultValue="DROP_REPLY">
                <option value="DROP_REPLY">DROP_REPLY (applied, reply lost)</option>
                <option value="REJECT">REJECT (refused, no effect)</option>
                <option value="DELAY">DELAY (reply held back)</option>
                <option value="DUPLICATE_REPLY">DUPLICATE_REPLY (reply twice)</option>
              </NativeSelect>
            </Field>
            <Field label="Command">
              <NativeSelect name="commandType" defaultValue="PostTransfer">
                {["PostTransfer", "ValidateTransaction", "HoldFunds", "CommitHold", "ReleaseHold", "CreditWallet", "ReverseTransaction", "QueryTransaction", "UnlockWallet", ""].map((c) => (
                  <option key={c} value={c}>{c || "any command"}</option>
                ))}
              </NativeSelect>
            </Field>
          </Row>
          <Row>
            <Field label="Times"><Input name="times" defaultValue="1" inputMode="numeric" /></Field>
            <Field label="Reason (REJECT)"><Input name="reasonCode" placeholder="INSUFFICIENT_BALANCE" /></Field>
            <Field label="Delay ms (DELAY)"><Input name="delayMs" inputMode="numeric" /></Field>
          </Row>
          <Row>
            <Button type="submit" name="op" value="add">Inject</Button>
            <Button type="submit" name="op" value="reset" variant="outline">Clear every fault</Button>
          </Row>
        </ActForm>
      </Panel>
      <Panel title="A wallet in core-mock" icon={Wallet}>
        <ActForm action={walletLookup}>
          <Field label="Wallet"><Input name="msisdn" defaultValue="01711000001" className="font-mono" /></Field>
          <Button type="submit" variant="outline" className="self-start">Show balance, hold and status</Button>
        </ActForm>
      </Panel>
      <Panel title="USSD SMPP gateway (gp)" icon={Radio}>
        <ActForm action={smpp}>
          <Row>
            <Button type="submit" name="op" value="binds" variant="outline">Show binds</Button>
            <Button type="submit" name="op" value="drop" variant="outline-destructive">Drop every bind</Button>
            <Button type="submit" name="op" value="throttle" variant="outline">Throttle next 2</Button>
            <Button type="submit" name="op" value="reset" variant="outline">Normal</Button>
          </Row>
        </ActForm>
      </Panel>
    </div>
  );
}
