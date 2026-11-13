import { CircleCheck, Filter, Inbox } from "lucide-react";
import Link from "next/link";
import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Field } from "@/components/ui/field";
import { Input, NativeSelect } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { diffLabel, mayDecide } from "@/lib/approvals";
import { formatAmount, formatTime } from "@/lib/format";
import { adminApi, operatorToken } from "@/lib/server/admin";
import { BASE_PATH } from "@/lib/server/env";
import type { Amount, ChangeRequest } from "@/lib/types";
import { ActionForm } from "../action-form";
import { approveRequest, rejectRequest } from "../actions";
import { ApiError } from "../api-error";

// Approvals inbox (spec 15.3, ticket 41): change requests newest first, PENDING by default, with
// the diff (status the maker saw -> status the action leads to) and what will be sent. A checker
// sees Approve / Reject on requests somebody else filed; the maker never on their own.

const FILTERS = ["PENDING", "APPROVED", "REJECTED", "EXPIRED", "all"] as const;

type Props = { searchParams: Promise<Record<string, string | string[] | undefined>> };

function one(v: string | string[] | undefined): string | undefined {
  return typeof v === "string" ? v : undefined;
}

function isAmount(v: unknown): v is Amount {
  return typeof v === "object" && v !== null && typeof (v as Amount).valueMinor === "number"
    && typeof (v as Amount).currency === "string";
}

function Resource({ cr }: { cr: ChangeRequest }) {
  if (cr.resourceType === "transaction") {
    return <Link className="font-mono text-xs" href={`/transactions/${cr.resourceId}`}>{cr.resourceId}</Link>;
  }
  if (cr.resourceType === "partner") {
    return <Link className="font-mono text-xs" href={`/partners/${encodeURIComponent(cr.resourceId)}`}>partner {cr.resourceId}</Link>;
  }
  if (cr.resourceType === "ussd_menu") {
    return <Link className="font-mono text-xs" href={`/ussd?menuId=${encodeURIComponent(cr.resourceId)}`}>ussd_menu {cr.resourceId}</Link>;
  }
  if (cr.resourceType === "notify_template") {
    const [code, channel, locale] = cr.resourceId.split("/");
    const q = new URLSearchParams({ code: code ?? "", channel: channel ?? "", locale: locale ?? "" });
    return <Link className="font-mono text-xs" href={`/templates?${q}`}>notify_template {cr.resourceId}</Link>;
  }
  return <span className="font-mono text-xs">{cr.resourceType} {cr.resourceId}</span>;
}

function Payload({ cr }: { cr: ChangeRequest }) {
  const p = cr.payload;
  const parts: string[] = [];
  if (isAmount(p.amount)) parts.push(formatAmount(p.amount));
  if (typeof p.reversalTxnId === "string") parts.push(`reversal ${p.reversalTxnId}`);
  if (typeof p.actionId === "string") parts.push(`action ${p.actionId}`);
  if (typeof p.commandId === "string") parts.push(`command ${p.commandId}`);
  if (cr.resourceType === "partner" && typeof p.version === "number") parts.push(`published v${p.version}`);
  if ((cr.resourceType === "ussd_menu" || cr.resourceType === "notify_template") && typeof p.version === "number") {
    parts.push(`${cr.status === "APPROVED" ? "published" : "version"} v${p.version}`);
  }
  if (cr.resourceType === "notify_template" && typeof p.body === "string") parts.push(p.body);
  return <span className="font-mono text-xs break-words">{parts.join(" / ") || "-"}</span>;
}

function Decision({ cr, operator, roles }: { cr: ChangeRequest; operator?: string; roles: string[] }) {
  if (mayDecide(cr, operator, roles)) {
    return (
      <div className="flex min-w-56 flex-col gap-2">
        <ActionForm action={approveRequest.bind(null, cr.id)} label={`Approve ${cr.id}`}>
          <Button type="submit" size="sm">Approve</Button>
        </ActionForm>
        <ActionForm action={rejectRequest.bind(null, cr.id)} label={`Reject ${cr.id}`} className="reject flex flex-col gap-1.5 border-t pt-2">
          <Field label="Rejection reason"><Input name="reason" required maxLength={500} className="h-8" /></Field>
          <Button type="submit" size="sm" variant="outline-destructive" className="self-start">Reject</Button>
        </ActionForm>
      </div>
    );
  }
  if (cr.status === "PENDING") {
    return <span className="text-xs text-muted-foreground italic">{cr.makerId === operator ? "your request: another checker decides" : "waiting for an ops-checker"}</span>;
  }
  return (
    <span className="text-xs tabular-nums">
      {cr.checkerId ? <>by {cr.checkerId}</> : null} {formatTime(cr.decidedAt)}
      {cr.decisionReason ? <div className="mt-0.5 text-muted-foreground">{cr.decisionReason}</div> : null}
    </span>
  );
}

export default async function ApprovalsPage({ searchParams }: Props) {
  const sp = await searchParams;
  const filter = FILTERS.find((f) => f === one(sp.status)) ?? "PENDING";
  const created = one(sp.created);
  const decided = one(sp.decided);

  const session = await auth();
  const operator = session?.user?.name ?? undefined;
  const roles = session?.roles ?? [];
  const token = await operatorToken();
  if (!token) redirect("/login?error=SessionExpired");
  const query = new URLSearchParams({ limit: "100" });
  if (filter !== "all") query.set("status", filter);
  const result = await adminApi().listChangeRequests(token, query);

  return (
    <>
      <PageHeader title="Approvals" description="Change requests filed by an ops-maker, applied only once a different operator (ops-checker) approves them." />
      {created ? (
        <Alert variant="success" role="status"><CircleCheck /><AlertDescription>Request filed. Nothing is applied until a different operator (ops-checker) approves it.</AlertDescription></Alert>
      ) : null}
      {decided ? <Alert variant="success" role="status"><CircleCheck /><AlertDescription>Decision recorded.</AlertDescription></Alert> : null}

      <Card>
        <form className="flex flex-wrap items-end gap-3 p-4" method="get" action={`${BASE_PATH}/approvals`}>
          <Field label="Status" className="w-48">
            <NativeSelect name="status" defaultValue={filter}>
              {FILTERS.map((f) => <option key={f} value={f}>{f === "all" ? "any" : f}</option>)}
            </NativeSelect>
          </Field>
          <Button type="submit"><Filter />Show</Button>
        </form>
      </Card>

      {!result.ok ? <ApiError status={result.status} error={result.error} /> : result.data.items.length === 0 ? (
        <div className="flex flex-col items-center gap-2 rounded-lg border border-dashed px-6 py-12 text-center text-muted-foreground">
          <Inbox className="size-6" />
          <p className="m-0">No {filter === "all" ? "" : `${filter.toLowerCase()} `}change requests.</p>
        </div>
      ) : (
        <Card className="overflow-hidden">
          <Table data-testid="change-requests">
            <TableHeader>
              <TableRow>
                <TableHead>Created</TableHead><TableHead>Action</TableHead><TableHead>Resource</TableHead><TableHead>Change</TableHead><TableHead>Sends</TableHead><TableHead>Maker</TableHead>
                <TableHead>Reason</TableHead><TableHead>Status</TableHead><TableHead>Expires</TableHead><TableHead>Decision</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {result.data.items.map((cr) => {
                const d = diffLabel(cr);
                return (
                  <TableRow key={cr.id} className={cr.id === created || cr.id === decided ? "highlight" : undefined}>
                    <TableCell className="whitespace-nowrap text-muted-foreground tabular-nums">{formatTime(cr.createdAt)}</TableCell>
                    <TableCell className="font-medium">{cr.action}</TableCell>
                    <TableCell><Resource cr={cr} /></TableCell>
                    <TableCell className="font-mono text-xs whitespace-nowrap" data-testid="diff"><span className="before text-muted-foreground">{d.before}</span> -&gt; <span className="after font-semibold">{d.after}</span></TableCell>
                    <TableCell className="max-w-64"><Payload cr={cr} /></TableCell>
                    <TableCell>{cr.makerId}</TableCell>
                    <TableCell className="max-w-64">{cr.reason}</TableCell>
                    <TableCell><StatusBadge status={cr.status} /></TableCell>
                    <TableCell className="whitespace-nowrap text-muted-foreground tabular-nums">{formatTime(cr.expiresAt)}</TableCell>
                    <TableCell><Decision cr={cr} operator={operator} roles={roles} /></TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </Card>
      )}
    </>
  );
}
