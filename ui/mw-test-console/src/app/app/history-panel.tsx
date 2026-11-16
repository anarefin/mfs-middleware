"use client";
// 4. History: the signed-in wallet's transactions through the app API (GET /app/v1/transactions,
// sent and received, every channel), newest first, filters type / direction, "More" pages on.
// Loaded on demand (a load binds the device the first time, as every app call), then again after
// every submit (refreshKey) once it was shown; a user change clears it.

import { History, Loader2, RefreshCw } from "lucide-react";
import { useEffect, useRef, useState, useTransition } from "react";
import { Hint, Panel, Row } from "@/components/section";
import { StatusBadge } from "@/components/status-badge";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Field } from "@/components/ui/field";
import { NativeSelect } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { DIRECTIONS, HISTORY_TYPES, appendPage, type HistoryItem, type HistoryPage } from "@/lib/app-history";
import { formatMinor } from "@/lib/format";
import type { Outcome } from "@/lib/types";
import { appRead } from "../actions/app";
import { Result, TxnLink } from "../components/result";

const when = (iso?: string) => {
  if (!iso) return "-";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString("en-GB", { dateStyle: "short", timeStyle: "medium" });
};

function counterparty(i: HistoryItem): string {
  const c = i.counterparty;
  const id = c.id ?? "-";
  return c.name ? `${c.name}, ${id}` : id;
}

export function HistoryPanel({ user, refreshKey }: { user: string; refreshKey: number }) {
  const [txnType, setTxnType] = useState("");
  const [direction, setDirection] = useState("");
  const [items, setItems] = useState<HistoryItem[] | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [next, setNext] = useState(0);
  const [failure, setFailure] = useState<Outcome | null>(null);
  const [loading, start] = useTransition();
  const shown = useRef(false);

  const load = (offset: number, filters = { txnType, direction }) =>
    start(async () => {
      const f = new FormData();
      f.set("user", user);
      f.set("what", "history");
      f.set("txnType", filters.txnType);
      f.set("direction", filters.direction);
      f.set("offset", String(offset));
      const o = await appRead(null, f);
      const page = (o?.data?.history ?? null) as HistoryPage | null;
      if (!o || !o.ok || !page) {
        setFailure(o);
        return;
      }
      setFailure(null);
      shown.current = true;
      setItems((cur) => (offset === 0 || !cur ? page.items : appendPage(cur, page.items)));
      setHasMore(page.hasMore);
      setNext(offset + page.items.length);
    });

  // a user change: nothing loaded for the new one yet
  useEffect(() => {
    shown.current = false;
    setItems(null);
    setFailure(null);
    setHasMore(false);
  }, [user]);

  // after a submit: reload from the top when the history was shown
  const loadRef = useRef(load);
  loadRef.current = load;
  useEffect(() => {
    if (refreshKey > 0 && shown.current) loadRef.current(0);
  }, [refreshKey]);

  const filter = (k: "txnType" | "direction", v: string) => {
    const next = { txnType, direction, [k]: v };
    if (k === "txnType") setTxnType(v);
    else setDirection(v);
    if (shown.current) load(0, next);
  };

  return (
    <Panel
      title="4. History"
      icon={History}
      data-testid="history"
      actions={
        <Button type="button" variant="outline" size="sm" onClick={() => load(0)} disabled={loading || !user} data-testid="history-load">
          {loading ? <Loader2 className="animate-spin" /> : <RefreshCw />}
          {items ? "Refresh" : "Load history"}
        </Button>
      }
    >
      <Row>
        <Field label="Type">
          <NativeSelect value={txnType} onChange={(e) => filter("txnType", e.target.value)} data-testid="history-type">
            <option value="">all types</option>
            {HISTORY_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
          </NativeSelect>
        </Field>
        <Field label="Direction">
          <NativeSelect value={direction} onChange={(e) => filter("direction", e.target.value)} data-testid="history-direction-filter">
            <option value="">sent and received</option>
            {DIRECTIONS.map((d) => <option key={d} value={d}>{d === "IN" ? "IN (received)" : "OUT (sent)"}</option>)}
          </NativeSelect>
        </Field>
      </Row>
      {failure ? <Result state={failure} /> : null}
      {items === null && !failure ? (
        <Hint>
          {user ? <>The transactions of <span className="font-mono">{user}</span>&apos;s wallet, sent (OUT) and received (IN) on every channel. Press Load history; it reloads after each submit.</> : "Choose who is signed in first."}
        </Hint>
      ) : null}
      {items && items.length === 0 ? <Hint data-testid="history-empty">No transactions{txnType || direction ? " for these filters" : ""}.</Hint> : null}
      {items && items.length > 0 ? (
        <div className="overflow-x-auto rounded-lg border">
          <Table>
            <TableHeader className="bg-muted/50">
              <TableRow>
                <TableHead>When</TableHead><TableHead>Dir</TableHead><TableHead>Type</TableHead><TableHead>Counterparty</TableHead>
                <TableHead className="text-right">Amount</TableHead><TableHead className="text-right">Fee</TableHead><TableHead>Status</TableHead><TableHead>txnId</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {items.map((i) => (
                <TableRow key={`${i.txnId}/${i.direction}`} data-testid={`history-row-${i.txnId}`}>
                  <TableCell className="whitespace-nowrap text-sm tabular-nums">{when(i.requestedAt)}</TableCell>
                  <TableCell>
                    <Badge tone={i.direction === "IN" ? "good" : "info"} data-testid="history-direction">{i.direction}</Badge>
                  </TableCell>
                  <TableCell className="font-mono text-sm">{i.txnType}</TableCell>
                  <TableCell className="text-sm">
                    <span className="font-mono">{counterparty(i)}</span>
                    {i.counterparty.walletType || i.counterparty.type ? <span className="text-muted-foreground"> · {(i.counterparty.walletType ?? i.counterparty.type ?? "").toLowerCase()}</span> : null}
                  </TableCell>
                  <TableCell className={`text-right whitespace-nowrap font-medium tabular-nums ${i.direction === "IN" ? "text-good" : ""}`}>
                    {i.direction === "IN" ? "+" : "-"}{formatMinor(i.amount.valueMinor)}
                  </TableCell>
                  <TableCell className="text-right whitespace-nowrap tabular-nums text-muted-foreground">{i.fee ? formatMinor(i.fee.valueMinor) : "-"}</TableCell>
                  <TableCell>
                    <StatusBadge status={i.status} data-testid="history-status" />
                    {i.error?.code ? <span className="block font-mono text-sm text-bad">{i.error.code}</span> : null}
                  </TableCell>
                  <TableCell><TxnLink txnId={i.txnId} /></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      ) : null}
      {items && hasMore ? (
        <Button type="button" variant="outline" onClick={() => load(next)} disabled={loading} data-testid="history-more">More</Button>
      ) : null}
    </Panel>
  );
}
