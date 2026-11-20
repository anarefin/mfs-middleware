"use client";

import { ClipboardCheck, FileSpreadsheet, ListChecks, Upload, UserRound } from "lucide-react";
import { useActionState, useEffect, useState } from "react";
import { Busy, EmptyResult, Hint, Panel, Row, TwoColumns } from "@/components/section";
import { Button } from "@/components/ui/button";
import { Field } from "@/components/ui/field";
import { Input, NativeSelect, Textarea } from "@/components/ui/input";
import { PORTAL_PERSONAS } from "@/lib/personas";
import { bulk } from "../actions/bulk";
import { Result } from "../components/result";

export function BulkTester() {
  const [state, run, pending] = useActionState(bulk, null);
  const [csv, setCsv] = useState("");
  const [batchId, setBatchId] = useState("");
  const [user, setUser] = useState("org-maker01");
  const previewLines = Array.isArray(state?.data?.previewRows) ? (state.data.previewRows as string[]) : [];
  // a generated sample or a new batch id lands in its field once, when that answer arrives
  useEffect(() => {
    const data = state?.data ?? {};
    if (typeof data.csv === "string") setCsv(data.csv);
    if (typeof data.batchId === "string") setBatchId(data.batchId);
  }, [state]);

  return (
    <TwoColumns>
      <div className="flex min-w-0 flex-col gap-5">
      {/* outside the form: React resets a form after its action, which would put the select back on
          its first option while the state (and the button label) still names the chosen user */}
      <Panel title="Portal user" icon={UserRound}>
        <NativeSelect value={user} onChange={(e) => setUser(e.target.value)} aria-label="Portal user" data-fill="user" data-testid="bulk-user">
          {PORTAL_PERSONAS.map((p) => <option key={p.user} value={p.user}>{p.label}</option>)}
        </NativeSelect>
      </Panel>
      <form action={run} className="flex flex-col gap-5" aria-busy={pending}>
        <input type="hidden" name="user" value={user} />
        <Panel title="1. File (maker)" icon={FileSpreadsheet}>
          <Row>
            <Field label="Rows"><Input name="rows" defaultValue="5" inputMode="numeric" className="tabular-nums" /></Field>
            <Field label="Tk each"><Input name="taka" defaultValue="1.00" className="tabular-nums" /></Field>
            <Button type="submit" variant="outline" name="op" value="sample" disabled={pending}>Generate sample</Button>
          </Row>
          <Field label="CSV (msisdn,amount,reference)"><Textarea name="csv" value={csv} onChange={(e) => setCsv(e.target.value)} data-testid="bulk-csv" className="min-h-32 font-mono text-sm" /></Field>
          <Button type="submit" name="op" value="upload" disabled={pending} data-testid="bulk-upload"><Upload />Upload as {user}</Button>
          <Hint>The upload is a PREVIEW: invalid rows are listed with their codes and never paid. Check it, then submit it for approval as the same maker.</Hint>
        </Panel>
        <Panel title="2. Submit, decide and follow" icon={ClipboardCheck}>
          <Field label="Batch id"><Input name="batchId" value={batchId} onChange={(e) => setBatchId(e.target.value)} data-testid="bulk-batch" className="font-mono" /></Field>
          <Row>
            <Button type="submit" name="op" value="preview" variant="outline" disabled={pending} data-testid="bulk-preview">Preview</Button>
            <Button type="submit" name="op" value="submit" disabled={pending} data-testid="bulk-submit">Submit (maker)</Button>
            <Button type="submit" name="op" value="approve" disabled={pending} data-testid="bulk-approve">Approve</Button>
            <Button type="submit" name="op" value="reject" variant="outline-destructive" disabled={pending}>Reject</Button>
            <Button type="submit" name="op" value="pause" variant="outline" disabled={pending}>Pause</Button>
            <Button type="submit" name="op" value="resume" variant="outline" disabled={pending}>Resume</Button>
          </Row>
          <Field label="Reject reason"><Input name="reason" placeholder="wrong payroll month" /></Field>
          <Row>
            <Button type="submit" name="op" value="get" variant="outline" disabled={pending} data-testid="bulk-get">Batch status</Button>
            <Button type="submit" name="op" value="result" variant="outline" disabled={pending}>Result file</Button>
            <Button type="submit" name="op" value="txns" variant="outline" disabled={pending}>Transactions</Button>
            <Button type="submit" name="op" value="list" variant="outline" disabled={pending}>List batches</Button>
          </Row>
          <Hint>Submit as org-maker01, then switch the user to org-checker01 to approve the upload. SP query: org-checker02 (ORG002 collects for DESCO and Karim Traders), empty batch id, Transactions.</Hint>
        </Panel>
      </form>
      </div>
      <div className="flex min-w-0 flex-col gap-5">
        {pending ? <Busy>working…</Busy> : null}
        {previewLines.length > 0 ? (
          <Panel title="Preview rows" icon={ListChecks}>
            <ul className="font-mono text-sm" data-testid="bulk-preview-rows">
              {previewLines.map((l) => <li key={l} className={l.includes(": invalid") ? "text-destructive" : undefined}>{l}</li>)}
            </ul>
          </Panel>
        ) : null}
        {state ? <Panel title="Result" icon={ListChecks}><Result state={state} /></Panel> : <EmptyResult>Generate a sample file, upload it as the maker, check the preview and submit it, approve it as the checker.</EmptyResult>}
      </div>
    </TwoColumns>
  );
}
