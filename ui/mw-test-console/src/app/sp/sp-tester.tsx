"use client";

import { FileSpreadsheet, ListChecks, Search, UserRound } from "lucide-react";
import { useActionState, useState } from "react";
import { Busy, EmptyResult, Hint, Panel, Row, TwoColumns } from "@/components/section";
import { Button } from "@/components/ui/button";
import { Field } from "@/components/ui/field";
import { Input, NativeSelect, Textarea } from "@/components/ui/input";
import { PORTAL_PERSONAS } from "@/lib/personas";
import { sp } from "../actions/sp";
import { Result } from "../components/result";

export function SpTester() {
  const [state, run, pending] = useActionState(sp, null);
  const [user, setUser] = useState("sp-viewer02");
  const csv = typeof state?.data?.csv === "string" ? state.data.csv : "";

  return (
    <TwoColumns>
      <div className="flex min-w-0 flex-col gap-5">
        <Panel title="Portal user" icon={UserRound}>
          <NativeSelect value={user} onChange={(e) => setUser(e.target.value)} aria-label="Portal user" data-fill="user" data-testid="sp-user">
            {PORTAL_PERSONAS.map((p) => <option key={p.user} value={p.user}>{p.label}</option>)}
          </NativeSelect>
        </Panel>
        <form action={run} className="flex flex-col gap-5" aria-busy={pending}>
          <input type="hidden" name="user" value={user} />
          <Panel title="Transactions" icon={Search}>
            <Row>
              <Field label="Status"><Input name="status" placeholder="COMPLETED" className="font-mono" /></Field>
              <Field label="From (ISO-8601)"><Input name="from" placeholder="2026-11-01T00:00:00Z" className="font-mono" /></Field>
              <Field label="To"><Input name="to" placeholder="2026-11-08T00:00:00Z" className="font-mono" /></Field>
            </Row>
            <Row>
              <Button type="submit" name="op" value="txns" disabled={pending} data-testid="sp-txns">List transactions</Button>
              <Button type="submit" name="op" value="report" variant="outline" disabled={pending} data-testid="sp-report">CSV report</Button>
            </Row>
            <Field label="Transaction id"><Input name="txnId" className="font-mono" data-testid="sp-txn-id" /></Field>
            <Button type="submit" name="op" value="txn" variant="outline" disabled={pending}>One transaction</Button>
            <Hint>The report covers [from, to), at most 31 days (default: the last 7 days). The org is always the token&apos;s: no field here names one.</Hint>
          </Panel>
        </form>
      </div>
      <div className="flex min-w-0 flex-col gap-5">
        {pending ? <Busy>working…</Busy> : null}
        {csv ? (
          <Panel title="Report" icon={FileSpreadsheet}>
            <Textarea readOnly value={csv} data-testid="sp-report-csv" className="min-h-40 font-mono text-xs" />
          </Panel>
        ) : null}
        {state ? <Panel title="Result" icon={ListChecks}><Result state={state} /></Panel> : <EmptyResult>Sign in as sp-viewer02 (ORG002) and list its transactions, or download the CSV report.</EmptyResult>}
      </div>
    </TwoColumns>
  );
}
