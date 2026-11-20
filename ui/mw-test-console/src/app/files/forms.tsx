"use client";
// The file mocks' forms (ticket 123): run now, AML sample, file faults, journal.

import { FileText, Play, ScrollText, TriangleAlert } from "lucide-react";
import { useActionState, type ReactNode } from "react";
import { Busy, Hint, Panel, Row } from "@/components/section";
import { Button } from "@/components/ui/button";
import { Field } from "@/components/ui/field";
import { Input, NativeSelect } from "@/components/ui/input";
import type { Outcome } from "@/lib/types";
import { FILE_FAULTS, FILE_PARTIES, MISMATCH_KINDS } from "@/lib/file-mocks";
import { addFileFault, amlSample, fileJournal, runFileMock } from "../actions/files";
import { Result } from "../components/result";

type Act = (prev: Outcome | null, f: FormData) => Promise<Outcome | null>;

function ActForm({ action, children, testid }: { action: Act; children: ReactNode; testid: string }) {
  const [state, run, pending] = useActionState(action, null);
  return (
    <form action={run} className="flex flex-col gap-3" aria-busy={pending} data-testid={testid}>
      <fieldset disabled={pending} className="contents">{children}</fieldset>
      {pending ? <Busy>working…</Busy> : null}
      <Result state={state} />
    </form>
  );
}

function PartySelect() {
  return (
    <Field label="File mock">
      <NativeSelect name="party" defaultValue="trustbank">
        {FILE_PARTIES.map((p) => <option key={p.name} value={p.name}>{p.name}: {p.title}</option>)}
      </NativeSelect>
    </Field>
  );
}

export function FileForms() {
  return (
    <div className="grid items-start gap-4 xl:grid-cols-2">
      <Panel title="Run a file mock now" icon={Play} contentClassName="gap-4">
        <ActForm action={runFileMock} testid="file-run">
          <PartySelect />
          <Row>
            <Field label="Operation (trust bank: mt940 or mt942)">
              <Input name="operation" list="file-operations" placeholder="mt940" />
            </Field>
            <Field label="Date (yyyy-mm-dd; MT940 default yesterday)"><Input name="date" placeholder="2026-11-20" className="font-mono" /></Field>
            <Field label="Cut time HH:mm (MT942)"><Input name="at" placeholder="14:00" className="font-mono" /></Field>
          </Row>
          <datalist id="file-operations">
            {FILE_PARTIES.flatMap((p) => p.operations).map((o) => <option key={o} value={o} />)}
          </datalist>
          <Hint>
            Trust bank: the statement of the SETTLEMENT wallet's ledger in trustbank/out. AML: answers every new file of aml/in.
            DW: checks every new control_&lt;date&gt;.json of dw/in. Needs partner-sftp (profile tester).
          </Hint>
          <Button type="submit" className="self-start">Run now</Button>
        </ActForm>
      </Panel>
      <Panel title="AML: upload a sample transaction file" icon={FileText} contentClassName="gap-4">
        <ActForm action={amlSample} testid="file-aml">
          <Field label="Amount per row (poisha; above 5000000 = ALERT)"><Input name="amountMinor" defaultValue="1000" inputMode="numeric" /></Field>
          <Hint>Three rows: payer 01711000777 (watch-list: HOLD), 01811116666 (ends 6666: BLOCK), 01711000002 (clean).</Hint>
          <Button type="submit" className="self-start">Upload and answer</Button>
        </ActForm>
      </Panel>
      <Panel title="File fault" icon={TriangleAlert} contentClassName="gap-4">
        <ActForm action={addFileFault} testid="file-fault">
          <PartySelect />
          <Row>
            <Field label="Operation (empty = all)"><Input name="operation" list="file-operations" /></Field>
            <Field label="Fault">
              <NativeSelect name="behaviour" defaultValue="MISSING_FILE">
                {FILE_FAULTS.map((b) => <option key={b} value={b}>{b}</option>)}
              </NativeSelect>
            </Field>
          </Row>
          <Row>
            <Field label="Mismatch kind (INJECTED_MISMATCH)">
              <Input name="kind" list="file-kinds" placeholder="AMOUNT_MISMATCH" />
            </Field>
            <Field label="Count"><Input name="count" inputMode="numeric" /></Field>
            <Field label="Delay ms (LATE_FILE)"><Input name="delayMs" inputMode="numeric" /></Field>
          </Row>
          <datalist id="file-kinds">
            {Object.values(MISMATCH_KINDS).flat().map((k) => <option key={k} value={k} />)}
          </datalist>
          <Row>
            <Field label="Key (business date yyyy-mm-dd or file name)"><Input name="key" className="font-mono" /></Field>
            <Field label="Next N files only"><Input name="nextN" inputMode="numeric" /></Field>
          </Row>
          <Button type="submit" className="self-start">Add fault</Button>
        </ActForm>
      </Panel>
      <Panel title="Journal" icon={ScrollText} contentClassName="gap-4">
        <ActForm action={fileJournal} testid="file-journal">
          <PartySelect />
          <Field label="Path contains (optional)"><Input name="path" placeholder="MT940_" /></Field>
          <Hint>Every file read (READ), written (WRITE), skipped (SKIP) or delayed (LATE) with its sha256, the fault and the DW verdict.</Hint>
          <div className="flex gap-2">
            <Button type="submit" name="op" value="read">Show journal</Button>
            <Button type="submit" name="op" value="reset" variant="outline">Reset party</Button>
          </div>
        </ActForm>
      </Panel>
    </div>
  );
}
