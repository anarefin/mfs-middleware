"use client";

import { ChevronRight, ListChecks, Loader2, Mail as MailIcon, MessageSquare, RefreshCw, Trash2 } from "lucide-react";
import { useActionState } from "react";
import { StatusBadge } from "@/components/status-badge";
import { Hint, Panel, Row } from "@/components/section";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Field } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { inbox } from "../actions/sim";
import { Result } from "../components/result";

type Sms = { destination?: string; source?: string; text?: string; encoding?: string; segments?: number; dlrStatus?: string; receivedAt?: string };
type Mail = { subject?: string; from?: string; receivedDate?: string; contentType?: string; mimeMessage?: string };

export function Inbox() {
  const [state, run, pending] = useActionState(inbox, null);
  const sms = (state?.data?.sms ?? []) as Sms[];
  const mail = (state?.data?.mail ?? []) as Mail[];
  return (
    <>
      <Card>
        <form action={run} className="flex flex-col gap-3 p-4" aria-busy={pending}>
          <Row>
            <Field label="Wallet / mobile number"><Input name="msisdn" defaultValue="01711000001" data-testid="inbox-msisdn" className="font-mono" /></Field>
            <Field label="Email address"><Input name="email" defaultValue="customer01@example.test" data-testid="inbox-email" /></Field>
            <Button type="submit" name="op" value="read" disabled={pending} data-testid="inbox-read">
              {pending ? <Loader2 className="animate-spin" /> : <RefreshCw />}
              {pending ? "reading…" : "Read"}
            </Button>
            <Button type="submit" name="op" value="clearSms" variant="outline-destructive" disabled={pending}><Trash2 />Clear every SMS</Button>
          </Row>
          <Hint>Realm users&apos; addresses: customer01@example.test … (mw-notify&apos;s dev contacts). A number ending 999 / 998 / 995 gets an UNDELIV / EXPIRED / no delivery report.</Hint>
        </form>
      </Card>
      {state ? (
        <div className="grid items-start gap-5 xl:grid-cols-2">
          <Panel title={`SMS (${sms.length})`} icon={MessageSquare} contentClassName="p-0">
            <Table data-testid="inbox-sms">
              <TableHeader className="bg-muted/50"><TableRow><TableHead>Received</TableHead><TableHead>From</TableHead><TableHead>Text</TableHead><TableHead>DLR</TableHead></TableRow></TableHeader>
              <TableBody>
                {sms.map((m, i) => (
                  <TableRow key={i}>
                    <TableCell className="font-mono text-xs whitespace-nowrap text-muted-foreground">{m.receivedAt}</TableCell>
                    <TableCell className="font-mono text-xs">{m.source}</TableCell>
                    <TableCell lang="bn" dir="auto">{m.text}<div className="mt-1 text-xs text-muted-foreground">{m.encoding} · {m.segments} part(s)</div></TableCell>
                    <TableCell>{m.dlrStatus ? <StatusBadge status={m.dlrStatus} /> : "-"}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Panel>
          <Panel title={`Email (${mail.length})`} icon={MailIcon} contentClassName="gap-2">
            {mail.map((m, i) => (
              <details key={i} className="group rounded-md border">
                <summary className="flex cursor-pointer list-none flex-wrap items-center gap-x-2 gap-y-1 px-3 py-2 text-sm [&::-webkit-details-marker]:hidden">
                  <ChevronRight className="size-3.5 shrink-0 text-muted-foreground transition-transform group-open:rotate-90" />
                  <strong className="font-medium">{m.subject}</strong> <span className="text-xs text-muted-foreground">{m.receivedDate} · {m.contentType?.split(";")[0]}</span>
                </summary>
                <pre className="m-0 max-h-96 overflow-auto rounded-b-md bg-screen px-3 py-2 font-mono text-xs whitespace-pre-wrap break-words text-screen-foreground">{m.mimeMessage?.slice(0, 6000)}</pre>
              </details>
            ))}
          </Panel>
          <Panel title="Calls" icon={ListChecks} className="xl:col-span-2"><Result state={state} /></Panel>
        </div>
      ) : null}
    </>
  );
}
