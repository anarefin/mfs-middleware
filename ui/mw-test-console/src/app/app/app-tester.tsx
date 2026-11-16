"use client";
// The mobile app: pick who is signed in (category, then one of the PIN-ready seeded wallets, or an
// MSISDN), bind the device, run one of the role's flows against a seeded counterparty or a magic
// outcome, see every step, and the wallet's history.

import { ArrowLeftRight, Loader2, RotateCcw, Search, Send, Smartphone, Store, UserRound, Wand2 } from "lucide-react";
import { useActionState, useEffect, useState } from "react";
import { Busy, EmptyResult, Hint, Panel, Row, TwoColumns } from "@/components/section";
import { Button } from "@/components/ui/button";
import { Field } from "@/components/ui/field";
import { Input, NativeSelect } from "@/components/ui/input";
import {
  BILLER_OPTIONS, COUNTERPARTY_LABEL, CUSTOM, DEFAULT_AMOUNT, GOOD_PIN, OPERATORS, counterpartyOptions, defaultCounterparty, scenarios,
} from "@/lib/app-flow-options";
import { APP_ROLES, ROLE_LABEL, msisdnOf, userLabel, usersOf, type AppUser } from "@/lib/app-users";
import { FILL_EVENT, type FillDetail } from "@/lib/how-to";
import { FLOWS_BY_ROLE, FLOW_HELP, type AppRole, type TxnType } from "@/lib/personas";
import { cn } from "@/lib/utils";
import { appRead, bindDevice, resendLast, submitTxn } from "../actions/app";
import { Result } from "../components/result";
import { HistoryPanel } from "./history-panel";
import { SignedInCard } from "./signed-in-card";

const OTHER = "__other";
const CHIP_TONE = { good: "border-good/40 text-good", bad: "border-bad/40 text-bad", warn: "border-warn/40 text-warn" } as const;

/** The flow form, re-mounted (key) per flow and user so its counterparty and amount start at that flow's defaults. */
function FlowForm({ type, user, me, users, busy, submitting, submit }: {
  type: TxnType; user: string; me?: string; users: readonly AppUser[]; busy: boolean; submitting: boolean; submit: (f: FormData) => void;
}) {
  const options = counterpartyOptions(type, me, users);
  const [cp, setCp] = useState(() => defaultCounterparty(type, me, users));
  const pick = options.some((o) => o.value === cp) ? cp : CUSTOM;
  const chips = scenarios(type, defaultCounterparty(type, me, users));
  const fill = (values: Record<string, string>) =>
    window.dispatchEvent(new CustomEvent<FillDetail>(FILL_EVENT, { detail: { page: "app", values } }));

  return (
    <form action={submit} className="flex flex-col gap-3">
      <input type="hidden" name="user" value={user} />
      <input type="hidden" name="txnType" value={type} />
      <div className="flex flex-col gap-1.5" data-testid="scenarios">
        <span className="text-sm font-medium text-muted-foreground">Scenario (fills counterparty, amount and PIN)</span>
        <div className="flex flex-wrap gap-1.5">
          {chips.map((c) => (
            <Button key={c.label} type="button" variant="outline" size="sm" className={cn("rounded-full", CHIP_TONE[c.tone])}
              onClick={() => fill(c.values)} data-testid="scenario-chip">
              <Wand2 />{c.label}
            </Button>
          ))}
        </div>
      </div>
      {type === "TOPUP" ? (
        <Row>
          <Field label="Operator"><NativeSelect name="operator" defaultValue="gp">{OPERATORS.map((o) => <option key={o}>{o}</option>)}</NativeSelect></Field>
          <Field label="Product"><NativeSelect name="product" defaultValue="PREPAID"><option>PREPAID</option><option>POSTPAID</option></NativeSelect></Field>
        </Row>
      ) : null}
      {type === "BILL_PAY" ? (
        <Field label="Biller"><NativeSelect name="biller" defaultValue="DESCO">{BILLER_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}</NativeSelect></Field>
      ) : null}
      {type === "ADD_MONEY_BANK" ? <input type="hidden" name="bank" value="c2wbank" /> : null}
      <Field label={COUNTERPARTY_LABEL[type]}>
        <NativeSelect value={pick} onChange={(e) => setCp(e.target.value === CUSTOM ? "" : e.target.value)} data-testid="counterparty-pick">
          {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          <option value={CUSTOM}>Custom…</option>
        </NativeSelect>
      </Field>
      <Input name="counterparty" value={cp} onChange={(e) => setCp(e.target.value)} data-fill="counterparty" data-testid="counterparty"
        className="font-mono" aria-label="counterparty value" placeholder={type === "BILL_PAY" ? "bill number" : type === "ADD_MONEY_BANK" ? "account number" : "01XXXXXXXXX"} />
      <Row>
        <Field label="Amount (Tk)"><Input name="amount" defaultValue={DEFAULT_AMOUNT[type]} data-testid="amount" className="tabular-nums" /></Field>
        <Field label="PIN"><Input name="pin" type="password" defaultValue={GOOD_PIN} autoComplete="off" data-testid="pin" /></Field>
      </Row>
      <Field label="Idempotency-Key (empty = new)"><Input name="idempotencyKey" placeholder="tc-…" className="font-mono" /></Field>
      <Button type="submit" disabled={busy} data-testid="submit">
        {submitting ? <Loader2 className="animate-spin" /> : <Send />}
        {submitting ? "running… (up to a minute with a timeout)" : "Submit"}
      </Button>
    </form>
  );
}

export function AppTester({ users }: { users: AppUser[] }) {
  const [role, setRole] = useState<AppRole>("customer");
  const [pick, setPick] = useState(() => usersOf(users, "customer")[0]?.user ?? OTHER);
  const [other, setOther] = useState("");
  const [type, setType] = useState<TxnType | null>("P2P");
  const [bindState, bind, binding] = useActionState(bindDevice, null);
  const [txnState, submit, submitting] = useActionState(submitTxn, null);
  const [resendState, resend, resending] = useActionState(resendLast, null);
  const [readState, read, reading] = useActionState(appRead, null);
  const [refreshKey, setRefreshKey] = useState(0);
  const busy = binding || submitting || resending || reading;

  const roleUsers = usersOf(users, role);
  const user = pick === OTHER ? other.trim() : pick;
  const me = msisdnOf(users, user);
  const signedIn = users.find((u) => u.user === user);
  const flows = FLOWS_BY_ROLE[role];
  const flow = type && flows.includes(type) ? type : null;

  const chooseRole = (r: AppRole) => {
    setRole(r);
    setPick(usersOf(users, r)[0]?.user ?? OTHER);
    setType(FLOWS_BY_ROLE[r][0] ?? null);
  };

  // every finished call may have moved money or bound a device: refresh the card and the history
  useEffect(() => {
    if (txnState || resendState || bindState) setRefreshKey((k) => k + 1);
  }, [txnState, resendState, bindState]);

  return (
    <div className="flex flex-col gap-5">
      <TwoColumns>
        <div className="flex min-w-0 flex-col gap-5">
          <Panel title="1. Who is signed in" icon={UserRound}>
            <Row>
              <Field label="Category">
                <NativeSelect value={role} onChange={(e) => chooseRole(e.target.value as AppRole)} data-fill="category" data-testid="app-category">
                  {APP_ROLES.map((r) => <option key={r} value={r}>{ROLE_LABEL[r]} ({usersOf(users, r).length})</option>)}
                </NativeSelect>
              </Field>
            </Row>
            <Field label="Wallet (app login at mw-auth, PIN-ready)">
              <NativeSelect value={pick} onChange={(e) => setPick(e.target.value)} data-fill="user" data-testid="app-user">
                {roleUsers.map((u) => <option key={u.user} value={u.user}>{userLabel(u)}</option>)}
                <option value={OTHER}>Other MSISDN…</option>
              </NativeSelect>
            </Field>
            {pick === OTHER ? (
              <Field label="MSISDN" hint="Any wallet with an app PIN at mw-auth (e.g. a fresh wallet of the Wallet auth page).">
                <Input value={other} onChange={(e) => setOther(e.target.value)} placeholder="01XXXXXXXXX" className="font-mono" data-testid="app-user-other" />
              </Field>
            ) : null}
            <SignedInCard user={user} name={signedIn?.name} refreshKey={refreshKey} />
            <form action={bind} className="flex flex-wrap gap-2">
              <input type="hidden" name="user" value={user} />
              <Button type="submit" variant="outline" disabled={busy} data-testid="bind"><Smartphone />{binding ? "binding…" : "Bind device"}</Button>
              <Button type="submit" variant="outline" name="force" value="1" disabled={busy}>Re-bind (new device)</Button>
            </form>
            <Hint>The first call binds automatically; binding here shows the OTP steps on their own.</Hint>
          </Panel>

          <Panel title="2. Flow" icon={ArrowLeftRight}>
            {flows.length === 0 ? (
              <p className="m-0 flex items-start gap-2 rounded-md bg-muted px-3 py-2 text-sm text-muted-foreground" data-testid="merchant-note">
                <Store className="mt-0.5 size-4 shrink-0" />
                A merchant starts no money flow in the app: pay it from a customer (Merchant payment) or the checkout page, then see it under Merchant
                collections (3) and as IN in the History (4).
              </p>
            ) : (
              <>
                <Field label="Transaction type">
                  <NativeSelect value={flow ?? ""} onChange={(e) => setType(e.target.value as TxnType)} data-fill="txnType" data-testid="txn-type">
                    {flows.map((t) => <option key={t} value={t}>{FLOW_HELP[t].title}</option>)}
                  </NativeSelect>
                </Field>
                {flow ? (
                  <>
                    <p className="m-0 rounded-md bg-muted px-3 py-2 text-sm text-muted-foreground">{FLOW_HELP[flow].hint}</p>
                    <FlowForm key={`${flow}|${user}`} type={flow} user={user} me={me} users={users} busy={busy} submitting={submitting} submit={submit} />
                  </>
                ) : null}
                <form action={resend}>
                  <input type="hidden" name="user" value={user} />
                  <Button type="submit" variant="outline" className="w-full" disabled={busy} data-testid="resend"><RotateCcw />{resending ? "resending…" : "Resend the last submit (same Idempotency-Key)"}</Button>
                </form>
              </>
            )}
          </Panel>

          <Panel title="3. Look things up" icon={Search}>
            <form action={read} className="flex flex-col gap-3">
              <input type="hidden" name="user" value={user} />
              <Row>
                <Button type="submit" variant="outline" name="what" value="billers" disabled={busy}>Billers</Button>
                <Button type="submit" variant={role === "merchant" ? "default" : "outline"} name="what" value="collections" disabled={busy} data-testid="collections">Merchant collections</Button>
              </Row>
              <Row>
                <Field label="Biller"><NativeSelect name="biller" defaultValue="DESCO">{BILLER_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.value}</option>)}</NativeSelect></Field>
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
      <HistoryPanel user={user} refreshKey={refreshKey} />
    </div>
  );
}
