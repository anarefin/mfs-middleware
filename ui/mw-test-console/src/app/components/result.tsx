// What an action did: the headline, the facts to check (status, balances, SMS) and every call it
// made through the edge, in order, each expandable to its answer.
// The ✔ / ✖ marks stay in the text (screen-reader only next to their icons): the e2e suite checks
// that a result does not contain ✖.

import { ChevronRight, CircleCheck, CircleX, ExternalLink, MessageSquare } from "lucide-react";
import { StatusBadge } from "@/components/status-badge";
import { Hint, SubHeading } from "@/components/section";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { formatMinor } from "@/lib/format";
import type { Outcome } from "@/lib/types";
import { cn } from "@/lib/utils";

export function Result({ state }: { state: Outcome | null }) {
  if (!state) return null;
  const consoleUrl = state.data?.consoleUrl as string | undefined;
  return (
    <div className="flex min-w-0 flex-col gap-3" aria-live="polite" data-testid="result">
      <p
        className={cn(
          "m-0 flex items-start gap-2 rounded-lg border px-3 py-2.5 text-sm font-medium break-words",
          state.ok ? "border-good/30 bg-good-bg text-good" : "border-bad/30 bg-bad-bg text-bad",
        )}
        data-testid="headline"
      >
        {state.ok ? <CircleCheck className="mt-0.5 size-4 shrink-0" /> : <CircleX className="mt-0.5 size-4 shrink-0" />}
        <span className="sr-only">{state.ok ? "✔ " : "✖ "}</span>
        <span className="min-w-0">{state.headline}</span>
      </p>
      {state.status ? (
        <p className="m-0 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
          <span className="text-muted-foreground">Final status:</span>
          <StatusBadge status={state.status} data-testid="txn-status" />
          {state.txnId ? <><span className="text-muted-foreground">· txnId</span><code className="font-mono text-xs" data-testid="txn-id">{state.txnId}</code></> : null}
          {consoleUrl ? (
            <>
              <span className="text-muted-foreground">·</span>
              <a href={consoleUrl} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 text-link">
                open in the ops console <ExternalLink className="size-3.5" />
              </a>
            </>
          ) : null}
        </p>
      ) : null}
      {state.data?.idempotencyKey ? <Hint>Idempotency-Key <code className="font-mono">{String(state.data.idempotencyKey)}</code></Hint> : null}
      {state.balances?.length ? (
        <div className="overflow-hidden rounded-lg border">
          <Table className="balances" data-testid="balances">
            <TableHeader className="bg-muted/50">
              <TableRow><TableHead>Wallet (core-mock)</TableHead><TableHead className="text-right">Before</TableHead><TableHead className="text-right">After</TableHead><TableHead className="text-right">Change</TableHead><TableHead className="text-right">Held now</TableHead></TableRow>
            </TableHeader>
            <TableBody>
              {state.balances.map((b) => {
                const d = b.beforeMinor !== null && b.afterMinor !== null ? b.afterMinor - b.beforeMinor : null;
                return (
                  <TableRow key={b.wallet}>
                    <TableCell className="font-mono text-xs">{b.wallet}</TableCell>
                    <TableCell className="text-right whitespace-nowrap tabular-nums">{b.beforeMinor === null ? "-" : formatMinor(b.beforeMinor)}</TableCell>
                    <TableCell className="text-right whitespace-nowrap tabular-nums">{b.afterMinor === null ? "-" : formatMinor(b.afterMinor)}</TableCell>
                    <TableCell
                      className={cn("text-right whitespace-nowrap font-medium tabular-nums", d ? (d > 0 ? "up text-good" : "down text-bad") : "text-muted-foreground")}
                      data-testid={`delta-${b.wallet}`}
                    >
                      {d === null ? "-" : formatMinor(d)}
                    </TableCell>
                    <TableCell className="text-right whitespace-nowrap tabular-nums">{b.heldMinor === null ? "-" : formatMinor(b.heldMinor)}</TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </div>
      ) : null}
      {state.sms?.length ? (
        <>
          <SubHeading>SMS received since the submit</SubHeading>
          <ul className="m-0 flex list-none flex-col gap-1.5 p-0" data-testid="sms">
            {state.sms.map((m, i) => (
              <li key={i} className="flex items-start gap-2 rounded-md bg-muted/60 px-3 py-2 text-sm">
                <MessageSquare className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
                <span className="min-w-0">
                  <span className="font-mono text-xs">{m.destination}</span> <span lang="bn" dir="auto">{m.text}</span>{" "}
                  <span className="text-xs text-muted-foreground">({m.dlrStatus ?? "no DLR yet"})</span>
                </span>
              </li>
            ))}
          </ul>
        </>
      ) : state.balances ? <Hint>No SMS yet (notifications can take a few seconds: see Inbox).</Hint> : null}
      <SubHeading>Steps</SubHeading>
      <ol className="m-0 flex list-none flex-col gap-1.5 p-0">
        {state.steps.map((s, i) => (
          <li key={i} className={cn("rounded-md border border-l-[3px] bg-card", s.ok ? "border-l-good" : "border-l-bad")}>
            <details className="group">
              <summary className="flex cursor-pointer list-none flex-wrap items-center gap-x-2 gap-y-1 px-3 py-2 text-sm [&::-webkit-details-marker]:hidden">
                <ChevronRight className="size-3.5 shrink-0 text-muted-foreground transition-transform group-open:rotate-90" />
                {s.ok ? <CircleCheck className="size-4 shrink-0 text-good" /> : <CircleX className="size-4 shrink-0 text-bad" />}
                <span className="sr-only">{s.ok ? "✔" : "✖"}</span>
                <span className="font-medium">{s.label}</span>
                {s.call ? <span className="font-mono text-xs text-muted-foreground">{s.call}</span> : null}
                {s.status !== undefined ? <span className={cn("font-mono text-xs font-semibold", s.ok ? "text-good" : "text-bad")}>HTTP {s.status}</span> : null}
                {s.ms !== undefined ? <span className="text-xs text-muted-foreground tabular-nums">{s.ms} ms</span> : null}
                {s.note ? <span className="text-xs text-muted-foreground">— {s.note}</span> : null}
              </summary>
              {s.body !== undefined ? (
                <pre className="m-0 max-h-96 overflow-auto rounded-b-md bg-screen px-3 py-2 font-mono text-xs whitespace-pre-wrap break-words text-screen-foreground">
                  {typeof s.body === "string" ? s.body : JSON.stringify(s.body, null, 2)}
                </pre>
              ) : null}
            </details>
          </li>
        ))}
      </ol>
    </div>
  );
}
