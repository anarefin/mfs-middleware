import { ArrowLeft, Eye, EyeOff, Handshake, ListTree, ScrollText } from "lucide-react";
import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { auth } from "@/auth";
import { StatusBadge, statusTone } from "@/components/status-badge";
import { Badge } from "@/components/ui/badge";
import { Card, CardHeader, CardSection, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { formatAmount, formatTime, partyLabel } from "@/lib/format";
import { canReveal } from "@/lib/oidc";
import { adminApi, operatorToken } from "@/lib/server/admin";
import { loadTxnView } from "@/lib/txn-view";
import type { TimelineEntry } from "@/lib/types";
import { cn } from "@/lib/utils";
import { requestTxnAction } from "../../actions";
import { ApiError } from "../../api-error";
import { TxnActions } from "./txn-actions";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

type Props = {
  params: Promise<{ txnId: string }>;
  searchParams: Promise<Record<string, string | string[] | undefined>>;
};

function step(e: TimelineEntry): string {
  if (e.kind === "STATE") return `${e.from ?? "(start)"} -> ${e.to ?? "?"}${e.trigger ? ` on ${e.trigger}` : ""}`;
  const sends = e.sends && e.sends > 1 ? `, sent ${e.sends}x` : "";
  const answer = e.result ? ` -> ${e.result}${e.reasonCode ? ` (${e.reasonCode})` : ""}` : " (no answer yet)";
  return `core ${e.command ?? "command"}${sends}${answer}`;
}

/** Timeline dot colour: CORE commands in the reversal tone, failures bad, manual review warn. */
function dotClass(e: TimelineEntry): string {
  if (e.outcome === "FAILURE") return "bg-bad";
  if (e.to === "MANUAL_REVIEW") return "bg-warn";
  if (e.kind === "CORE") return "bg-rev";
  return "bg-muted-foreground";
}

function outcomeClass(outcome: string | undefined): string | undefined {
  if (outcome === "FAILURE") return "text-bad";
  if (outcome === "SUCCESS") return "text-good";
  return undefined;
}

/** A partner-call or audit outcome (SUCCESS, FAILURE, ...) as a tinted pill; the text stays the raw value. */
function OutcomeBadge({ outcome }: { outcome: string }) {
  const o = outcome.toUpperCase();
  const tone = o === "SUCCESS" ? "good" : o === "FAILURE" || o === "ERROR" ? "bad" : statusTone(outcome);
  return <Badge tone={tone} dot>{outcome}</Badge>;
}

function BackLink() {
  return (
    <p className="m-0 text-sm">
      <Link href="/transactions" className="inline-flex items-center gap-1 text-muted-foreground hover:text-foreground"><ArrowLeft className="size-3.5" />Back to search</Link>
    </p>
  );
}

function Fact({ label, children, className, ...props }: { label: string; children: React.ReactNode; className?: string; "data-testid"?: string }) {
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className={cn("m-0 min-w-0 break-words", className)} {...props}>{children}</dd>
    </div>
  );
}

function EmptyNote({ children }: { children: React.ReactNode }) {
  return <p className="m-0 px-4 py-6 text-center text-sm text-muted-foreground">{children}</p>;
}

export default async function TransactionPage({ params, searchParams }: Props) {
  const { txnId } = await params;
  if (!UUID.test(txnId)) notFound();
  const session = await auth();
  const mayReveal = canReveal(session?.roles ?? []);
  const reveal = mayReveal && (await searchParams).reveal === "true";

  const token = await operatorToken();
  if (!token) redirect("/login?error=SessionExpired");
  const result = await loadTxnView(adminApi(), token, txnId, session?.roles ?? [], reveal);

  if (!result.ok) {
    return (
      <>
        <BackLink />
        <h1 className="m-0 font-mono text-lg font-semibold">{txnId}</h1>
        <ApiError status={result.status} error={result.error} />
      </>
    );
  }

  const { transaction: t, timeline, partnerCalls, auditTrail, revealed } = result.data;
  return (
    <>
      <div className="flex flex-col gap-2">
        <BackLink />
        <h1 className="m-0 flex flex-wrap items-baseline gap-x-2 text-xl font-semibold tracking-tight">Transaction <span className="font-mono text-base font-normal text-muted-foreground">{t.txnId}</span></h1>
      </div>

      <CardSection aria-label="Summary">
        <dl className="m-0 grid grid-cols-[repeat(auto-fill,minmax(200px,1fr))] gap-x-6 gap-y-4 p-4 text-sm">
          <Fact label="Status" data-testid="status"><StatusBadge status={t.status}>{t.status}{t.previousStatus ? ` (was ${t.previousStatus})` : ""}</StatusBadge></Fact>
          <Fact label="Type / channel">{t.txnType} / {t.channel}</Fact>
          <Fact label="Amount" className="tabular-nums">{formatAmount(t.amount)}</Fact>
          <Fact label="Fee" className="tabular-nums">{formatAmount(t.fee)}</Fact>
          <Fact label="Created" className="tabular-nums">{formatTime(t.createdAt)}</Fact>
          <Fact label="Updated" className="tabular-nums">{formatTime(t.updatedAt)}</Fact>
          <Fact label="Initiator" className="font-mono text-xs" data-testid="initiator">{partyLabel(t.initiator)}</Fact>
          <Fact label="Debit / credit" className="font-mono text-xs">{partyLabel(t.debitParty)} / {partyLabel(t.creditParty)}</Fact>
          <Fact label="Partner">{[t.partner?.partnerId, t.partner?.productCode, t.partner?.billerCode].filter(Boolean).join(" / ")}</Fact>
          <Fact label="Partner ref / core ref" className="font-mono text-xs">{t.partnerRef ?? "-"} / {t.coreRef ?? "-"}</Fact>
          <Fact label="Correlation ID" className="font-mono text-xs">{t.correlationId ?? ""}</Fact>
          <Fact label="Error" className={t.error?.code ? "text-bad" : undefined}>{t.error?.code ? `${t.error.code} ${t.error.message ?? ""}` : "-"}</Fact>
          {t.originalTxnId ? <Fact label="Reversal of" className="font-mono text-xs"><Link href={`/transactions/${t.originalTxnId}`}>{t.originalTxnId}</Link></Fact> : null}
          {t.reversalTxnId ? <Fact label="Reversed by" className="font-mono text-xs"><Link href={`/transactions/${t.reversalTxnId}`}>{t.reversalTxnId}</Link></Fact> : null}
        </dl>
        {mayReveal ? (
          <p className="m-0 flex flex-wrap items-center gap-2 border-t px-4 py-3 text-sm">
            {revealed
              ? <><EyeOff className="size-4 text-warn" /><strong className="font-medium text-warn">MSISDNs shown unmasked; this view was audited.</strong> <Link href={`/transactions/${t.txnId}`}>Mask again</Link></>
              : <><Eye className="size-4 text-muted-foreground" /><Link href={`/transactions/${t.txnId}?reveal=true`}>Reveal MSISDN (audited)</Link></>}
          </p>
        ) : null}
      </CardSection>

      {/* operator actions (ticket 41): only for an ops-maker, only those the status allows */}
      <TxnActions gate={result.gate} action={requestTxnAction.bind(null, t.txnId)} />

      <Card>
        <CardHeader><CardTitle><ListTree />Saga timeline</CardTitle></CardHeader>
        {timeline.length === 0 ? <EmptyNote>No steps recorded.</EmptyNote> : (
          <ol className="relative m-0 list-none p-4 pl-8 before:absolute before:top-5 before:bottom-5 before:left-[1.3rem] before:w-px before:bg-border before:content-['']" data-testid="timeline">
            {timeline.map((e, i) => (
              <li key={i} className={cn("relative flex flex-wrap items-baseline gap-x-3 gap-y-0.5 py-1.5 text-sm", e.kind)}>
                <span aria-hidden className={cn("absolute top-[0.8rem] -left-[0.9rem] size-2 rounded-full ring-4 ring-card", dotClass(e))} />
                <span className="font-mono text-xs text-muted-foreground tabular-nums">{formatTime(e.at)}</span>{" "}
                <span className={cn(e.kind === "CORE" && "text-rev", outcomeClass(e.outcome))}>{step(e)}</span>
              </li>
            ))}
          </ol>
        )}
      </Card>

      <Card className="overflow-hidden">
        <CardHeader><CardTitle><Handshake />Partner calls</CardTitle></CardHeader>
        {partnerCalls.length === 0 ? <EmptyNote>No partner calls.</EmptyNote> : (
          <Table data-testid="partner-calls">
            <TableHeader><TableRow><TableHead>Sent</TableHead><TableHead>Operation</TableHead><TableHead>Partner</TableHead><TableHead>Outcome</TableHead><TableHead>Partner ref</TableHead><TableHead>Error</TableHead><TableHead>Answered</TableHead></TableRow></TableHeader>
            <TableBody>
              {partnerCalls.map((c) => (
                <TableRow key={c.requestId}>
                  <TableCell className="whitespace-nowrap text-muted-foreground tabular-nums">{formatTime(c.sentAt)}</TableCell><TableCell>{c.operation}</TableCell><TableCell>{c.partnerId ?? c.family}</TableCell>
                  <TableCell><OutcomeBadge outcome={c.outcome ?? "pending"} /></TableCell><TableCell className="font-mono text-xs">{c.partnerRef ?? ""}</TableCell>
                  <TableCell className="font-mono text-xs text-bad">{c.errorCode ?? ""}</TableCell><TableCell className="whitespace-nowrap text-muted-foreground tabular-nums">{formatTime(c.answeredAt)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Card>

      <Card className="overflow-hidden">
        <CardHeader><CardTitle><ScrollText />Audit trail</CardTitle></CardHeader>
        {auditTrail.length === 0 ? <EmptyNote>No audit records.</EmptyNote> : (
          <Table data-testid="audit-trail">
            <TableHeader><TableRow><TableHead>At</TableHead><TableHead>Action</TableHead><TableHead>Actor</TableHead><TableHead>Outcome</TableHead><TableHead>Service</TableHead><TableHead>Resource</TableHead></TableRow></TableHeader>
            <TableBody>
              {auditTrail.map((a) => (
                <TableRow key={a.auditId}>
                  <TableCell className="whitespace-nowrap text-muted-foreground tabular-nums">{formatTime(a.at)}</TableCell><TableCell className="font-mono text-xs">{a.action}</TableCell><TableCell>{a.actor.type} {a.actor.id}</TableCell>
                  <TableCell><OutcomeBadge outcome={a.outcome} /></TableCell><TableCell className="text-muted-foreground">{a.service}</TableCell><TableCell className="font-mono text-xs">{a.resource}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Card>
    </>
  );
}
