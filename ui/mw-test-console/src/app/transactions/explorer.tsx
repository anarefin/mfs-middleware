"use client";
// The transaction explorer: a search form (txnId, MSISDN, status, type), the hits, and the detail
// of the one opened: summary, saga timeline, partner calls, audit trail (mw-admin-api's answer).
// ?txnId= (every txnId link of the other pages) searches that id and opens it at once.

import { ArrowRight, History, ListChecks, Network, ScrollText, Search } from "lucide-react";
import Link from "next/link";
import { startTransition, useActionState, useEffect, type ReactNode } from "react";
import { StatusBadge } from "@/components/status-badge";
import { Busy, EmptyResult, Hint, Panel, Row } from "@/components/section";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Field } from "@/components/ui/field";
import { Input, NativeSelect } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { formatMinor } from "@/lib/format";
import { TXN_STATUSES, TXN_TYPE_SUGGESTIONS, txnHref, type Amount, type Party, type TimelineEntry, type TxnDetail, type TxnSearch } from "@/lib/txn-query";
import { explore } from "../actions/txn";
import { Result } from "../components/result";

const money = (a?: Amount) => (a ? `${formatMinor(a.valueMinor)}${a.currency && a.currency !== "BDT" ? ` ${a.currency}` : ""}` : "-");
const party = (p?: Party) => (p ? [p.id, p.type ? `(${p.type}${p.walletType ? ` ${p.walletType}` : ""})` : ""].filter(Boolean).join(" ") || "-" : "-");
const when = (s?: string) => (s ? s.replace("T", " ").replace(/\.\d+Z$/, "Z") : "-");

function SearchFields({ search }: { search?: TxnSearch }) {
  return (
    <>
      {(["txnId", "msisdn", "status", "txnType"] as const).map((k) => (search?.[k] ? <input key={k} type="hidden" name={k} value={search[k]} /> : null))}
    </>
  );
}

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex min-w-0 flex-col gap-0.5">
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className="m-0 min-w-0 break-words">{children}</dd>
    </div>
  );
}

function timelineText(e: TimelineEntry): ReactNode {
  if (e.kind === "CORE") {
    return (
      <>
        core <code className="font-mono">{e.command}</code>, sent {e.sends ?? 1}x <ArrowRight className="inline size-3.5" />{" "}
        {e.result ?? e.outcome ?? "no answer yet"}{e.reasonCode ? ` (${e.reasonCode})` : ""}
      </>
    );
  }
  return (
    <>
      {e.from ? <StatusBadge status={e.from} /> : <span className="text-muted-foreground">start</span>} <ArrowRight className="inline size-3.5" />{" "}
      {e.to ? <StatusBadge status={e.to} /> : "-"}{e.trigger ? <span className="text-muted-foreground"> on {e.trigger}</span> : null}
    </>
  );
}

function Detail({ d }: { d: TxnDetail }) {
  const x = d.transaction;
  return (
    <div className="flex min-w-0 flex-col gap-5" data-testid="txn-detail">
      <Panel title={<>Transaction <code className="font-mono text-sm font-normal">{x.txnId}</code></>} icon={ListChecks}>
        <dl className="m-0 grid gap-x-6 gap-y-3 sm:grid-cols-2 xl:grid-cols-3">
          <Fact label="Status">
            <StatusBadge status={x.status} data-testid="detail-status" />
            {x.previousStatus ? <span className="text-muted-foreground"> (was {x.previousStatus})</span> : null}
          </Fact>
          <Fact label="Type / channel">{x.txnType} · {x.channel}</Fact>
          <Fact label="Amount / fee"><span className="tabular-nums">{money(x.amount)}</span> / <span className="tabular-nums">{money(x.fee)}</span></Fact>
          <Fact label="Initiator"><span className="font-mono">{party(x.initiator)}</span></Fact>
          <Fact label="Debit party"><span className="font-mono">{party(x.debitParty)}</span></Fact>
          <Fact label="Credit party"><span className="font-mono">{party(x.creditParty)}</span></Fact>
          <Fact label="Created / updated (UTC)">{when(x.createdAt)}<br />{when(x.updatedAt)}</Fact>
          <Fact label="Partner">{x.partner ? [x.partner.partnerId, x.partner.productCode, x.partner.billerCode].filter(Boolean).join(" · ") : "-"}</Fact>
          <Fact label="Partner ref / core ref"><span className="font-mono">{x.partnerRef ?? "-"} / {x.coreRef ?? "-"}</span></Fact>
          <Fact label="Correlation ID"><span className="font-mono text-sm">{x.correlationId ?? "-"}</span></Fact>
          {x.error ? (
            <Fact label="Error">
              <code className="font-mono text-bad">{x.error.code}</code> {x.error.message}
              {x.error.messageBn ? <span lang="bn" className="block text-muted-foreground">{x.error.messageBn}</span> : null}
            </Fact>
          ) : null}
          {x.originalTxnId ? <Fact label="Reversal of"><Link href={txnHref(x.originalTxnId)} className="font-mono text-sm">{x.originalTxnId}</Link></Fact> : null}
          {x.reversalTxnId ? <Fact label="Reversed by"><Link href={txnHref(x.reversalTxnId)} className="font-mono text-sm">{x.reversalTxnId}</Link></Fact> : null}
        </dl>
        {x.attributes && Object.keys(x.attributes).length ? (
          <Hint>Attributes: {Object.entries(x.attributes).map(([k, v]) => `${k}=${v}`).join(", ")}</Hint>
        ) : null}
        <Hint>
          MSISDNs as mw-admin-api shows them to {d.revealed ? "a revealing operator (unmasked, audited)" : "ops-e2e-maker01: masked"}. The same in the{" "}
          <a href={`/console/transactions/${x.txnId}`} target="_blank" rel="noreferrer">ops console ↗</a>.
        </Hint>
      </Panel>

      <Panel title={`Saga timeline (${d.timeline.length})`} icon={History}>
        {d.timeline.length ? (
          <ol className="m-0 flex list-none flex-col gap-1.5 p-0" data-testid="txn-timeline">
            {d.timeline.map((e, i) => (
              <li key={i} className={`flex flex-wrap items-center gap-x-3 gap-y-1 rounded-md border-l-[3px] bg-muted/60 px-3 py-2 ${e.kind === "CORE" ? "border-l-info" : "border-l-muted-foreground"}`}>
                <span className="font-mono text-sm text-muted-foreground tabular-nums">{when(e.at)}</span>
                <span className="min-w-0">{timelineText(e)}</span>
              </li>
            ))}
          </ol>
        ) : <Hint>No steps recorded.</Hint>}
      </Panel>

      <Panel title={`Partner calls (${d.partnerCalls.length})`} icon={Network} contentClassName={d.partnerCalls.length ? "p-0" : undefined}>
        {d.partnerCalls.length ? (
          <Table data-testid="txn-partner-calls">
            <TableHeader className="bg-muted/50">
              <TableRow><TableHead>Sent</TableHead><TableHead>Operation</TableHead><TableHead>Partner</TableHead><TableHead>Outcome</TableHead><TableHead>Partner ref</TableHead><TableHead>Error</TableHead><TableHead>Answered</TableHead></TableRow>
            </TableHeader>
            <TableBody>
              {d.partnerCalls.map((c, i) => (
                <TableRow key={c.requestId ?? i}>
                  <TableCell className="font-mono text-sm whitespace-nowrap">{when(c.sentAt)}</TableCell>
                  <TableCell>{c.operation}{c.family ? <span className="text-muted-foreground"> ({c.family})</span> : null}</TableCell>
                  <TableCell>{c.partnerId}</TableCell>
                  <TableCell>{c.outcome ? <StatusBadge status={c.outcome} /> : "-"}</TableCell>
                  <TableCell className="font-mono text-sm">{c.partnerRef ?? "-"}</TableCell>
                  <TableCell className="font-mono text-sm">{c.errorCode ?? "-"}</TableCell>
                  <TableCell className="font-mono text-sm whitespace-nowrap">{when(c.answeredAt)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        ) : <Hint>No partner calls.</Hint>}
      </Panel>

      <Panel
        title={`Audit trail (${d.auditTrail.length})`}
        icon={ScrollText}
        contentClassName={d.auditTrail.length ? "p-0" : undefined}
        actions={d.auditAnchor?.status ? (
          <Badge tone={d.auditAnchor.status === "INTACT" ? "good" : d.auditAnchor.status === "BROKEN" ? "bad" : "neutral"} title={d.auditAnchor.verifiedAt ? `Checked ${d.auditAnchor.verifiedAt}` : undefined} data-testid="audit-anchor">
            anchors {d.auditAnchor.status}{d.auditAnchor.lastAnchorDate ? ` (signed to ${d.auditAnchor.lastAnchorDate})` : ""}
          </Badge>
        ) : null}
      >
        {d.auditTrail.length ? (
          <Table data-testid="txn-audit">
            <TableHeader className="bg-muted/50">
              <TableRow><TableHead>At</TableHead><TableHead>Action</TableHead><TableHead>Actor</TableHead><TableHead>Outcome</TableHead><TableHead>Service</TableHead><TableHead>Resource</TableHead></TableRow>
            </TableHeader>
            <TableBody>
              {d.auditTrail.map((a, i) => (
                <TableRow key={a.auditId ?? i}>
                  <TableCell className="font-mono text-sm whitespace-nowrap">{when(a.at)}</TableCell>
                  <TableCell>
                    <details>
                      <summary className="cursor-pointer font-mono text-sm">{a.action}</summary>
                      <pre className="m-0 mt-1 max-h-72 overflow-auto rounded-md bg-screen px-2 py-1 font-mono text-sm whitespace-pre-wrap break-words text-screen-foreground">
                        {JSON.stringify({ before: a.before, after: a.after }, null, 2)}
                      </pre>
                    </details>
                  </TableCell>
                  <TableCell className="font-mono text-sm">{a.actor ? `${a.actor.type}:${a.actor.id}` : "-"}</TableCell>
                  <TableCell>{a.outcome ?? "-"}</TableCell>
                  <TableCell>{a.service ?? "-"}</TableCell>
                  <TableCell className="font-mono text-sm break-all">{a.resource ?? "-"}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        ) : <Hint>No audit entries yet (mw-audit-dw records them a few seconds after each step).</Hint>}
      </Panel>
    </div>
  );
}

export function Explorer({ initialTxnId }: { initialTxnId?: string }) {
  const [state, run, pending] = useActionState(explore, null);

  useEffect(() => {
    if (!initialTxnId) return;
    const f = new FormData();
    f.set("op", "search");
    f.set("txnId", initialTxnId);
    startTransition(() => run(f));
  }, [initialTxnId, run]);

  const s = state?.search;
  const items = state?.items;
  return (
    <div className="flex min-w-0 flex-col gap-5">
      <Panel title="Search" icon={Search}>
        <form action={run} className="flex flex-col gap-3" aria-busy={pending} key={initialTxnId ?? "none"}>
          <input type="hidden" name="op" value="search" />
          <Row>
            <Field label="txnId (UUID)" className="min-w-[18rem]!">
              <Input name="txnId" defaultValue={initialTxnId ?? ""} className="font-mono" placeholder="0f8fad5b-…" data-testid="tx-txnid" />
            </Field>
            <Field label="MSISDN"><Input name="msisdn" className="font-mono" placeholder="01711000001" data-testid="tx-msisdn" /></Field>
            <Field label="Status">
              <NativeSelect name="status" defaultValue="" data-testid="tx-status">
                <option value="">any</option>
                {TXN_STATUSES.map((x) => <option key={x} value={x}>{x}</option>)}
              </NativeSelect>
            </Field>
            <Field label="Type">
              <Input name="txnType" list="tx-types" className="font-mono" placeholder="any" data-testid="tx-type" />
              <datalist id="tx-types">{TXN_TYPE_SUGGESTIONS.map((x) => <option key={x} value={x} />)}</datalist>
            </Field>
            <Button type="submit" disabled={pending} data-testid="tx-search"><Search />{pending ? "searching…" : "Search"}</Button>
          </Row>
          <Hint>Newest first, 50 per page. A search by txnId opens the transaction at once.</Hint>
        </form>
      </Panel>

      {pending ? <Busy>Working… (the first search signs in to realm ops; a used one-time code may mean up to 30 s)</Busy> : null}

      {items ? (
        <Panel
          title={`Results${s ? ` (${s.offset + 1}-${s.offset + items.length})` : ""}`}
          icon={ListChecks}
          contentClassName={items.length ? "p-0" : undefined}
          actions={s ? (
            <div className="flex gap-2">
              <form action={run}>
                <SearchFields search={s} />
                <input type="hidden" name="offset" value={Math.max(0, s.offset - s.limit)} />
                <Button type="submit" variant="outline" size="sm" disabled={pending || s.offset === 0}>Previous</Button>
              </form>
              <form action={run}>
                <SearchFields search={s} />
                <input type="hidden" name="offset" value={s.offset + s.limit} />
                <Button type="submit" variant="outline" size="sm" disabled={pending || items.length < s.limit}>Next</Button>
              </form>
            </div>
          ) : null}
        >
          {items.length ? (
            <Table data-testid="tx-results">
              <TableHeader className="bg-muted/50">
                <TableRow><TableHead>Created (UTC)</TableHead><TableHead>Transaction ID</TableHead><TableHead>Type</TableHead><TableHead>Channel</TableHead><TableHead>Status</TableHead><TableHead className="text-right">Amount</TableHead><TableHead>Initiator</TableHead><TableHead>Partner ref</TableHead></TableRow>
              </TableHeader>
              <TableBody>
                {items.map((x) => (
                  <TableRow key={x.txnId} className={state?.detail?.transaction.txnId === x.txnId ? "highlight" : undefined}>
                    <TableCell className="font-mono text-sm whitespace-nowrap">{when(x.createdAt)}</TableCell>
                    <TableCell>
                      <form action={run}>
                        <input type="hidden" name="op" value="detail" />
                        <input type="hidden" name="txnId" value={x.txnId} />
                        <button type="submit" className="cursor-pointer font-mono text-sm text-link hover:underline" disabled={pending} data-testid="tx-open">{x.txnId}</button>
                      </form>
                    </TableCell>
                    <TableCell>{x.txnType}</TableCell>
                    <TableCell>{x.channel}</TableCell>
                    <TableCell><StatusBadge status={x.status} /></TableCell>
                    <TableCell className="text-right whitespace-nowrap tabular-nums">{money(x.amount)}</TableCell>
                    <TableCell className="font-mono text-sm">{x.initiator?.id ?? "-"}</TableCell>
                    <TableCell className="font-mono text-sm">{x.partnerRef ?? "-"}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : <Hint>No transactions found.</Hint>}
        </Panel>
      ) : null}

      {state?.detail ? <Detail d={state.detail} /> : null}

      {state ? (
        <Panel title="Calls" icon={ListChecks}>
          <Result state={{ ok: state.ok, headline: state.headline, steps: state.steps }} />
        </Panel>
      ) : !pending ? <EmptyResult>Search by txnId or MSISDN, or click a txnId in any result of the other pages.</EmptyResult> : null}
    </div>
  );
}
