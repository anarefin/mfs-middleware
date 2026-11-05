import Link from "next/link";
import { redirect } from "next/navigation";
import { auth } from "@/auth";
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
    return <Link className="mono" href={`/transactions/${cr.resourceId}`}>{cr.resourceId}</Link>;
  }
  return <span className="mono">{cr.resourceType} {cr.resourceId}</span>;
}

function Payload({ cr }: { cr: ChangeRequest }) {
  const p = cr.payload;
  const parts: string[] = [];
  if (isAmount(p.amount)) parts.push(formatAmount(p.amount));
  if (typeof p.reversalTxnId === "string") parts.push(`reversal ${p.reversalTxnId}`);
  if (typeof p.actionId === "string") parts.push(`action ${p.actionId}`);
  if (typeof p.commandId === "string") parts.push(`command ${p.commandId}`);
  return <span className="mono">{parts.join(" / ") || "-"}</span>;
}

function Decision({ cr, operator, roles }: { cr: ChangeRequest; operator?: string; roles: string[] }) {
  if (mayDecide(cr, operator, roles)) {
    return (
      <div className="decide">
        <ActionForm action={approveRequest.bind(null, cr.id)} label={`Approve ${cr.id}`}>
          <button className="primary" type="submit">Approve</button>
        </ActionForm>
        <ActionForm action={rejectRequest.bind(null, cr.id)} label={`Reject ${cr.id}`} className="reject">
          <label>Rejection reason<input name="reason" required maxLength={500} /></label>
          <button type="submit">Reject</button>
        </ActionForm>
      </div>
    );
  }
  if (cr.status === "PENDING") {
    return <span className="hint">{cr.makerId === operator ? "your request: another checker decides" : "waiting for an ops-checker"}</span>;
  }
  return (
    <span>
      {cr.checkerId ? <>by {cr.checkerId}</> : null} {formatTime(cr.decidedAt)}
      {cr.decisionReason ? <div className="hint">{cr.decisionReason}</div> : null}
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
      <h1>Approvals</h1>
      {created ? (
        <p className="notice" role="status">Request filed. Nothing is applied until a different operator (ops-checker) approves it.</p>
      ) : null}
      {decided ? <p className="notice" role="status">Decision recorded.</p> : null}

      <form className="panel search" method="get" action={`${BASE_PATH}/approvals`}>
        <label>Status
          <select name="status" defaultValue={filter}>
            {FILTERS.map((f) => <option key={f} value={f}>{f === "all" ? "any" : f}</option>)}
          </select>
        </label>
        <button className="primary" type="submit">Show</button>
      </form>

      {!result.ok ? <ApiError status={result.status} error={result.error} /> : result.data.items.length === 0 ? (
        <p className="hint">No {filter === "all" ? "" : `${filter.toLowerCase()} `}change requests.</p>
      ) : (
        <table data-testid="change-requests">
          <thead>
            <tr>
              <th>Created</th><th>Action</th><th>Resource</th><th>Change</th><th>Sends</th><th>Maker</th>
              <th>Reason</th><th>Status</th><th>Expires</th><th>Decision</th>
            </tr>
          </thead>
          <tbody>
            {result.data.items.map((cr) => {
              const d = diffLabel(cr);
              return (
                <tr key={cr.id} className={cr.id === created || cr.id === decided ? "highlight" : undefined}>
                  <td>{formatTime(cr.createdAt)}</td>
                  <td>{cr.action}</td>
                  <td><Resource cr={cr} /></td>
                  <td data-testid="diff"><span className="before">{d.before}</span> -&gt; <span className="after">{d.after}</span></td>
                  <td><Payload cr={cr} /></td>
                  <td>{cr.makerId}</td>
                  <td>{cr.reason}</td>
                  <td className={`status ${cr.status}`}>{cr.status}</td>
                  <td>{formatTime(cr.expiresAt)}</td>
                  <td><Decision cr={cr} operator={operator} roles={roles} /></td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </>
  );
}
