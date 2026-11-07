"use client";
// The simulator, core-mock and SMPP control forms, each showing what its calls did.

import { useActionState, type ReactNode } from "react";
import type { Outcome } from "@/lib/types";
import { coreFault, readPartner, replayCallback, setScenario, smpp, walletLookup } from "../actions/sim";
import { Result } from "../components/result";

type Act = (prev: Outcome | null, f: FormData) => Promise<Outcome | null>;

export function ActForm({ action, children, testid }: { action: Act; children: ReactNode; testid?: string }) {
  const [state, run, pending] = useActionState(action, null);
  return (
    <form action={run} className="stack" aria-busy={pending} data-testid={testid}>
      <fieldset disabled={pending}>{children}</fieldset>
      {pending ? <p className="busy">working…</p> : null}
      <Result state={state} />
    </form>
  );
}

const PARTNER_INFO: Record<string, string> = {
  ocs: "OCS (prepaid top-up, every MNO)",
  billagg: "Bill aggregator (DESCO, DPDC, WASA, TITAS)",
  c2wbank: "C2W bank (add money from bank)",
};

export function PartnerForms() {
  return (
    <div className="grid">
      {Object.entries(PARTNER_INFO).map(([p, title]) => (
        <section key={p} className="panel">
          <h3>{title}</h3>
          <ActForm action={setScenario} testid={`scenario-${p}`}>
            <input type="hidden" name="partner" value={p} />
            <div className="row">
              <label>Outcome (forceMode)
                <select name="forceMode" defaultValue="">
                  <option value="">normal (magic values decide)</option>
                  <option value="SUCCESS">SUCCESS</option>
                  <option value="BUSINESS_FAILURE">BUSINESS_FAILURE</option>
                  <option value="TIMEOUT">TIMEOUT (applied, reply held)</option>
                  <option value="SERVER_ERROR_AFTER_SEND">500 after send (applied)</option>
                  <option value="UNKNOWN_CODE">unknown code 9876</option>
                  <option value="MALFORMED">malformed body (applied)</option>
                </select>
              </label>
              <label>Latency ms<input name="latencyMs" inputMode="numeric" placeholder="0" /></label>
            </div>
            <div className="row">
              <label>Error rate 0..1 (503 unapplied)<input name="errorRate" placeholder="0" /></label>
              <label>Timeout hold ms<input name="timeoutDelayMs" inputMode="numeric" placeholder="default" /></label>
            </div>
            <label className="inline"><input type="checkbox" name="outage" /> outage (every request 503, not applied)</label>
            {p !== "ocs" ? (
              <>
                <div className="row">
                  <label>Callback
                    <select name="callbackMode" defaultValue={p === "c2wbank" ? "ONCE" : "NONE"}>
                      <option value="NONE">none</option>
                      <option value="ONCE">once</option>
                      <option value="DUPLICATE">twice (duplicate)</option>
                      <option value="LATE">late (after the delay)</option>
                      <option value="OUT_OF_ORDER">outcome then stale PENDING</option>
                    </select>
                  </label>
                  <label>Callback delay ms<input name="callbackDelayMs" inputMode="numeric" placeholder="0" /></label>
                </div>
                {p === "billagg" ? <label className="inline"><input type="checkbox" name="callbackOnly" /> callback-only (payments answered 202 PENDING)</label> : null}
              </>
            ) : null}
            <div className="row">
              <button type="submit" name="op" value="set" className="primary">Set {p}</button>
              <button type="submit" name="op" value="reset">Reset {p}</button>
            </div>
          </ActForm>
          <h3>What {p} received</h3>
          <ActForm action={readPartner}>
            <input type="hidden" name="partner" value={p} />
            <button type="submit">Show scenario and last 10 requests</button>
          </ActForm>
          {p !== "ocs" ? (
            <>
              <h3>Replay a callback</h3>
              <ActForm action={replayCallback}>
                <input type="hidden" name="partner" value={p} />
                <label>Partner reference (the transactionId {p} received, see the requests)<input name="reference" /></label>
                <button type="submit">Replay</button>
              </ActForm>
            </>
          ) : null}
        </section>
      ))}
    </div>
  );
}

export function CoreForms() {
  return (
    <div className="grid">
      <section className="panel">
        <h3>Inject a core-mock fault</h3>
        <ActForm action={coreFault} testid="core-fault">
          <div className="row">
            <label>Mode
              <select name="mode" defaultValue="DROP_REPLY">
                <option value="DROP_REPLY">DROP_REPLY (applied, reply lost)</option>
                <option value="REJECT">REJECT (refused, no effect)</option>
                <option value="DELAY">DELAY (reply held back)</option>
                <option value="DUPLICATE_REPLY">DUPLICATE_REPLY (reply twice)</option>
              </select>
            </label>
            <label>Command
              <select name="commandType" defaultValue="PostTransfer">
                {["PostTransfer", "ValidateTransaction", "HoldFunds", "CommitHold", "ReleaseHold", "CreditWallet", "ReverseTransaction", "QueryTransaction", "UnlockWallet", ""].map((c) => (
                  <option key={c} value={c}>{c || "any command"}</option>
                ))}
              </select>
            </label>
          </div>
          <div className="row">
            <label>Times<input name="times" defaultValue="1" inputMode="numeric" /></label>
            <label>Reason (REJECT)<input name="reasonCode" placeholder="INSUFFICIENT_BALANCE" /></label>
            <label>Delay ms (DELAY)<input name="delayMs" inputMode="numeric" /></label>
          </div>
          <div className="row">
            <button type="submit" name="op" value="add" className="primary">Inject</button>
            <button type="submit" name="op" value="reset">Clear every fault</button>
          </div>
        </ActForm>
      </section>
      <section className="panel">
        <h3>A wallet in core-mock</h3>
        <ActForm action={walletLookup}>
          <label>Wallet<input name="msisdn" defaultValue="01711000001" /></label>
          <button type="submit">Show balance, hold and status</button>
        </ActForm>
      </section>
      <section className="panel">
        <h3>USSD SMPP gateway (gp)</h3>
        <ActForm action={smpp}>
          <div className="row">
            <button type="submit" name="op" value="binds">Show binds</button>
            <button type="submit" name="op" value="drop" className="danger">Drop every bind</button>
            <button type="submit" name="op" value="throttle">Throttle next 2</button>
            <button type="submit" name="op" value="reset">Normal</button>
          </div>
        </ActForm>
      </section>
    </div>
  );
}
