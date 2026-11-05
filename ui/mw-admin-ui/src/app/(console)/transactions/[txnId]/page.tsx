import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { auth } from "@/auth";
import { ACTION_LABELS, canMake, txnActionsFor } from "@/lib/approvals";
import { formatAmount, formatTime, partyLabel } from "@/lib/format";
import { canReveal } from "@/lib/oidc";
import { adminApi, operatorToken } from "@/lib/server/admin";
import type { TimelineEntry } from "@/lib/types";
import { ActionForm } from "../../action-form";
import { requestTxnAction } from "../../actions";
import { ApiError } from "../../api-error";

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

export default async function TransactionPage({ params, searchParams }: Props) {
  const { txnId } = await params;
  if (!UUID.test(txnId)) notFound();
  const session = await auth();
  const mayReveal = canReveal(session?.roles ?? []);
  const reveal = mayReveal && (await searchParams).reveal === "true";

  const token = await operatorToken();
  if (!token) redirect("/login?error=SessionExpired");
  const result = await adminApi().getTransaction(token, txnId, reveal);

  if (!result.ok) {
    return (
      <>
        <p><Link href="/transactions">Back to search</Link></p>
        <h1 className="mono">{txnId}</h1>
        <ApiError status={result.status} error={result.error} />
      </>
    );
  }

  const { transaction: t, timeline, partnerCalls, auditTrail, revealed } = result.data;
  // operator actions (ticket 41): only for an ops-maker, only those the status allows, and not
  // while a request is open (mw-admin-api allows one per transaction)
  const pendingQuery = new URLSearchParams({ resourceId: t.txnId, status: "PENDING", limit: "1" });
  const pending = await adminApi().listChangeRequests(token, pendingQuery);
  const open = pending.ok ? pending.data.items[0] : undefined;
  const actions = canMake(session?.roles ?? []) && !open ? txnActionsFor(t) : [];
  return (
    <>
      <p><Link href="/transactions">Back to search</Link></p>
      <h1>Transaction <span className="mono">{t.txnId}</span></h1>

      <section className="panel" aria-label="Summary">
        <dl className="facts">
          <dt>Status</dt><dd className={`status ${t.status}`} data-testid="status">{t.status}{t.previousStatus ? ` (was ${t.previousStatus})` : ""}</dd>
          <dt>Type / channel</dt><dd>{t.txnType} / {t.channel}</dd>
          <dt>Amount</dt><dd>{formatAmount(t.amount)}</dd>
          <dt>Fee</dt><dd>{formatAmount(t.fee)}</dd>
          <dt>Created</dt><dd>{formatTime(t.createdAt)}</dd>
          <dt>Updated</dt><dd>{formatTime(t.updatedAt)}</dd>
          <dt>Initiator</dt><dd data-testid="initiator">{partyLabel(t.initiator)}</dd>
          <dt>Debit / credit</dt><dd>{partyLabel(t.debitParty)} / {partyLabel(t.creditParty)}</dd>
          <dt>Partner</dt><dd>{[t.partner?.partnerId, t.partner?.productCode, t.partner?.billerCode].filter(Boolean).join(" / ")}</dd>
          <dt>Partner ref / core ref</dt><dd className="mono">{t.partnerRef ?? "-"} / {t.coreRef ?? "-"}</dd>
          <dt>Correlation ID</dt><dd className="mono">{t.correlationId ?? ""}</dd>
          <dt>Error</dt><dd>{t.error?.code ? `${t.error.code} ${t.error.message ?? ""}` : "-"}</dd>
          {t.originalTxnId ? <><dt>Reversal of</dt><dd className="mono"><Link href={`/transactions/${t.originalTxnId}`}>{t.originalTxnId}</Link></dd></> : null}
          {t.reversalTxnId ? <><dt>Reversed by</dt><dd className="mono"><Link href={`/transactions/${t.reversalTxnId}`}>{t.reversalTxnId}</Link></dd></> : null}
        </dl>
        {mayReveal ? (
          <p>
            {revealed
              ? <><strong>MSISDNs shown unmasked; this view was audited.</strong> <Link href={`/transactions/${t.txnId}`}>Mask again</Link></>
              : <Link href={`/transactions/${t.txnId}?reveal=true`}>Reveal MSISDN (audited)</Link>}
          </p>
        ) : null}
      </section>

      {open ? (
        <p className="notice" data-testid="pending-request">
          {open.action} requested by {open.makerId} on {formatTime(open.createdAt)}, waiting for approval in{" "}
          <Link href="/approvals">Approvals</Link>.
        </p>
      ) : null}
      {actions.length > 0 ? (
        <section className="panel actions" aria-label="Actions">
          <h2>Actions</h2>
          <p className="hint">A request is applied only after a different operator (ops-checker) approves it.</p>
          <ActionForm action={requestTxnAction.bind(null, t.txnId)} label="Request an action">
            <label>Reason<input name="reason" required maxLength={500} /></label>
            <span className="buttons">
              {actions.map((a) => (
                <button key={a} className="primary" type="submit" name="action" value={a}>{ACTION_LABELS[a]}</button>
              ))}
            </span>
          </ActionForm>
        </section>
      ) : null}

      <h2>Saga timeline</h2>
      {timeline.length === 0 ? <p className="hint">No steps recorded.</p> : (
        <ol className="timeline" data-testid="timeline">
          {timeline.map((e, i) => (
            <li key={i} className={e.kind}>
              <span className="hint mono">{formatTime(e.at)}</span>{" "}
              <span className={e.outcome ? `outcome-${e.outcome}` : undefined}>{step(e)}</span>
            </li>
          ))}
        </ol>
      )}

      <h2>Partner calls</h2>
      {partnerCalls.length === 0 ? <p className="hint">No partner calls.</p> : (
        <table data-testid="partner-calls">
          <thead><tr><th>Sent</th><th>Operation</th><th>Partner</th><th>Outcome</th><th>Partner ref</th><th>Error</th><th>Answered</th></tr></thead>
          <tbody>
            {partnerCalls.map((c) => (
              <tr key={c.requestId}>
                <td>{formatTime(c.sentAt)}</td><td>{c.operation}</td><td>{c.partnerId ?? c.family}</td>
                <td>{c.outcome ?? "pending"}</td><td className="mono">{c.partnerRef ?? ""}</td>
                <td>{c.errorCode ?? ""}</td><td>{formatTime(c.answeredAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <h2>Audit trail</h2>
      {auditTrail.length === 0 ? <p className="hint">No audit records.</p> : (
        <table data-testid="audit-trail">
          <thead><tr><th>At</th><th>Action</th><th>Actor</th><th>Outcome</th><th>Service</th><th>Resource</th></tr></thead>
          <tbody>
            {auditTrail.map((a) => (
              <tr key={a.auditId}>
                <td>{formatTime(a.at)}</td><td>{a.action}</td><td>{a.actor.type} {a.actor.id}</td>
                <td className={`outcome-${a.outcome}`}>{a.outcome}</td><td>{a.service}</td><td className="mono">{a.resource}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}
