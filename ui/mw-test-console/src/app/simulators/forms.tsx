"use client";
// The mock-control (every mock party of mw-simulators), core-mock and SMPP forms, each showing what its calls did.

import { Building2, Gauge, Radio, Wallet } from "lucide-react";
import { useActionState, type ReactNode } from "react";
import { Busy, Hint, Panel, Row } from "@/components/section";
import { Button } from "@/components/ui/button";
import { Field, InlineField } from "@/components/ui/field";
import { Input, NativeSelect } from "@/components/ui/input";
import type { Outcome } from "@/lib/types";
import { BEHAVIOURS, MOCK_PARTIES, SWITCH_STATES } from "@/lib/mock-parties";
import { addScenario, coreFault, partyState, replayCallback, setSwitch, smpp, walletLookup } from "../actions/sim";
import { remit } from "../actions/remit";
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

const checkboxClass = "size-4 accent-primary";

function PartySelect({ only }: { only?: (p: (typeof MOCK_PARTIES)[number]) => boolean }) {
  const list = only ? MOCK_PARTIES.filter(only) : MOCK_PARTIES;
  return (
    <Field label="Mock party">
      <NativeSelect name="party" defaultValue={list[0]?.name}>
        {list.map((p) => <option key={p.name} value={p.name}>{p.name}: {p.title}</option>)}
      </NativeSelect>
    </Field>
  );
}

/** The mock-control API of mw-simulators (ADR D33): a scenario, the switch and settings, the state and journal, callback replay. */
export function PartnerForms() {
  return (
    <div className="grid items-start gap-4 xl:grid-cols-2">
      <Panel title="Add a scenario" icon={Building2} contentClassName="gap-4">
        <ActForm action={addScenario} testid="mock-scenario">
          <PartySelect />
          <Row>
            <Field label="Operation (empty = every operation)">
              <Input name="operation" list="mock-operations" placeholder="topup, pay, debit, ipn, bind, submit, command…" />
            </Field>
            <Field label="Behaviour">
              <NativeSelect name="behaviour" defaultValue="BUSINESS_ERROR">
                {BEHAVIOURS.map((b) => <option key={b} value={b}>{b}</option>)}
              </NativeSelect>
            </Field>
          </Row>
          <datalist id="mock-operations">
            {[...new Set(MOCK_PARTIES.flatMap((p) => p.operations))].map((o) => <option key={o} value={o} />)}
          </datalist>
          <Row>
            <Field label="Code (BUSINESS_ERROR)"><Input name="code" placeholder="e.g. 9876, 0x58, 68" /></Field>
            <Field label="Delay ms (TIMEOUT, DELAY, LATE_CALLBACK)"><Input name="delayMs" inputMode="numeric" /></Field>
            <Field label="HTTP status (HTTP_5XX, merchant)"><Input name="httpStatus" inputMode="numeric" /></Field>
          </Row>
          <Row>
            <Field label="Key (MSISDN, account, biller, command…; empty = global)"><Input name="key" className="font-mono" /></Field>
            <Field label="Next N calls only"><Input name="nextN" inputMode="numeric" /></Field>
          </Row>
          <Hint>
            Codes: {MOCK_PARTIES.map((p) => `${p.name} ${p.codeHint}`).join("; ")}. A scenario wins over the magic values while it lasts;
            the most specific one decides (next N, then key, then operation, then party-wide).
          </Hint>
          <Button type="submit" className="self-start">Add scenario</Button>
        </ActForm>
      </Panel>
      <Panel title="Switch and settings" icon={Gauge} contentClassName="gap-4">
        <ActForm action={setSwitch} testid="mock-switch">
          <PartySelect />
          <Row>
            <Field label="State">
              <NativeSelect name="state" defaultValue="UP">
                {SWITCH_STATES.map((s) => <option key={s} value={s}>{s}</option>)}
              </NativeSelect>
            </Field>
            <Field label="Latency min ms"><Input name="latencyMinMs" inputMode="numeric" placeholder="0" /></Field>
            <Field label="Latency max ms"><Input name="latencyMaxMs" inputMode="numeric" placeholder="= min" /></Field>
          </Row>
          <Row>
            <Field label="Share of calls DOWN / MAINTENANCE hits (0..1)"><Input name="ratio" placeholder="1" /></Field>
            <Field label="Timeout hold ms (ussdgw: screen wait)"><Input name="timeoutDelayMs" inputMode="numeric" placeholder="default" /></Field>
          </Row>
          <InlineField><input type="checkbox" name="callbacks" className={checkboxClass} /> callbacks to the middleware (billagg, c2wbank)</InlineField>
          <InlineField><input type="checkbox" name="callbackOnly" className={checkboxClass} /> billagg callback-only (payments answered 202 PENDING)</InlineField>
          <Hint>DOWN closes the connection unanswered; MAINTENANCE is the party&apos;s maintenance answer (503, SMPP 0x08, payShield 68); nothing is applied in either.</Hint>
          <Button type="submit" className="self-start">Set switch and settings</Button>
        </ActForm>
      </Panel>
      <Panel title="State, journal and reset" icon={Radio} contentClassName="gap-4">
        <ActForm action={partyState} testid="mock-state">
          <PartySelect />
          <Row>
            <Button type="submit" name="op" value="show" variant="outline">Show scenarios, switch, settings, last 10 calls</Button>
            <Button type="submit" name="op" value="normal" variant="outline">Normal</Button>
            <Button type="submit" name="op" value="reset" variant="outline-destructive">Reset (journal and ledgers too)</Button>
          </Row>
        </ActForm>
      </Panel>
      <Panel title="Replay a callback" icon={Building2} contentClassName="gap-4">
        <ActForm action={replayCallback} testid="mock-replay">
          <PartySelect only={(p) => !!p.callbacks} />
          <Field label="Partner reference (the transactionId the partner received, see the journal)"><Input name="reference" className="font-mono" /></Field>
          <Button type="submit" variant="outline" className="self-start">Replay</Button>
        </ActForm>
      </Panel>
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

/** The console plays the remittance partner ucb-imt (tickets 119b/119c): look up, then send. */
export function RemitForm() {
  const [state, run, pending] = useActionState(remit, null);
  return (
    <Panel title="Send remittance (partner ucb-imt)" icon={Building2}>
      <form action={run} className="flex flex-col gap-3" aria-busy={pending} data-testid="remit-form">
        <fieldset disabled={pending} className="contents">
          <Row>
            <Field label="Beneficiary wallet"><Input name="beneficiary" defaultValue="01711000001" className="font-mono" data-testid="remit-beneficiary" /></Field>
            <Field label="Amount (Tk)"><Input name="amount" defaultValue="250" className="tabular-nums" data-testid="remit-amount" /></Field>
          </Row>
          <Row>
            <Field label="Sender name"><Input name="sender" defaultValue="Test Sender" /></Field>
            <Field label="Sender country"><Input name="country" defaultValue="AE" /></Field>
          </Row>
          <Row>
            <Button type="submit" name="op" value="lookup" variant="outline" data-testid="remit-lookup">Look up beneficiary</Button>
            <Button type="submit" name="op" value="send" data-testid="remit-send">Send remittance</Button>
          </Row>
          <Hint>Signed as the partner (HMAC, UCB_IMT_HMAC_SECRET). The lookup shows only the masked name and number. Over Tk 2,00,000 the core rejects it (REJECTED); a credit comes from SETTLEMENT_UCB_IMT.</Hint>
        </fieldset>
        {pending ? <Busy>working…</Busy> : null}
        <Result state={state} />
      </form>
    </Panel>
  );
}
