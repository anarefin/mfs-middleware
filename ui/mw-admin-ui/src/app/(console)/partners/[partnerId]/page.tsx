import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { auth } from "@/auth";
import { canMakePartner } from "@/lib/approvals";
import { formatTime } from "@/lib/format";
import { adminApi, operatorToken } from "@/lib/server/admin";
import { ActionForm } from "../../action-form";
import { requestPartnerChange } from "../../actions";
import { ApiError } from "../../api-error";
import { openRequest, PendingNotice } from "../../pending-notice";

// One partner (tickets 38 / 42): the published config (no secrets: credential refs only) and, for
// ops-maker / integration-maker, enable / disable and timeouts as change requests.

const PARTNER_ID = /^[a-z0-9][a-z0-9-]{0,63}$/;

type Props = { params: Promise<{ partnerId: string }> };

export default async function PartnerPage({ params }: Props) {
  const { partnerId } = await params;
  if (!PARTNER_ID.test(partnerId)) notFound();
  const session = await auth();
  const token = await operatorToken();
  if (!token) redirect("/login?error=SessionExpired");
  const result = await adminApi().getPartner(token, partnerId);
  if (!result.ok) {
    return (
      <>
        <p><Link href="/partners">Back to partners</Link></p>
        <h1 className="mono">{partnerId}</h1>
        <ApiError status={result.status} error={result.error} />
      </>
    );
  }
  const p = result.data;
  const c = p.config;
  const enabled = c.enabled !== false;
  const open = await openRequest(token, "partner", partnerId);
  const maker = canMakePartner(session?.roles ?? []);

  return (
    <>
      <p><Link href="/partners">Back to partners</Link></p>
      <h1>Partner <span className="mono">{p.partnerId}</span></h1>

      <section className="panel" aria-label="Config">
        <dl className="facts">
          <dt>State</dt><dd className="status" data-testid="partner-state">{enabled ? "enabled" : "disabled"}</dd>
          <dt>Family</dt><dd>{p.family}</dd>
          <dt>Version</dt><dd data-testid="partner-version">{p.version === 0 ? "seed (never published)" : `v${p.version}`}</dd>
          <dt>Changed</dt><dd>{p.updatedBy}{p.approvedBy ? `, approved by ${p.approvedBy}` : ""}, {formatTime(p.updatedAt)}</dd>
          <dt>Base URL</dt><dd className="mono">{c.baseUrl}</dd>
          <dt>Template</dt><dd>{c.template ?? "rest-json-request-reply"}</dd>
          <dt>Timeouts</dt>
          <dd data-testid="partner-timeouts">
            connect {c.connectTimeoutMs ?? "-"} ms, call {c.timeoutMs ?? "-"} ms, read {c.readTimeoutMs ?? "-"} ms
          </dd>
          <dt>Auth</dt><dd>{c.auth?.scheme ?? "none"}{c.auth?.credentialsRef ? <> (secret <span className="mono">{c.auth.credentialsRef}</span>)</> : null}</dd>
          <dt>Operations</dt>
          <dd className="mono">{Object.entries(c.operations).map(([k, o]) => `${k} ${o.method} ${o.path}`).join(", ")}</dd>
          <dt>Error map</dt><dd>{Object.keys(c.errorMap ?? {}).length} codes</dd>
        </dl>
      </section>

      {open ? <PendingNotice cr={open} /> : null}
      {maker && !open ? (
        <section className="panel actions" aria-label="Change partner">
          <h2>Change</h2>
          <p className="hint">Applied only after a different operator (ops-checker or integration-checker) approves it.</p>
          <ActionForm action={requestPartnerChange.bind(null, p.partnerId)} label={enabled ? "Disable partner" : "Enable partner"}>
            <label>Reason<input name="reason" required maxLength={500} /></label>
            <span className="buttons">
              <button className="primary" type="submit" name="action" value={enabled ? "DISABLE" : "ENABLE"}>
                {enabled ? "Request disable" : "Request enable"}
              </button>
            </span>
          </ActionForm>
          <h2>Timeouts</h2>
          <ActionForm action={requestPartnerChange.bind(null, p.partnerId)} className="search" label="Partner timeouts">
            <input type="hidden" name="action" value="SET_TIMEOUTS" />
            <label>Connect (ms)<input name="connectTimeoutMs" inputMode="numeric" defaultValue={c.connectTimeoutMs ?? ""} /></label>
            <label>Call timeout (ms)<input name="timeoutMs" inputMode="numeric" defaultValue={c.timeoutMs ?? ""} /></label>
            <label>Read (ms)<input name="readTimeoutMs" inputMode="numeric" defaultValue={c.readTimeoutMs ?? ""} /></label>
            <label>Reason<input name="reason" required maxLength={500} /></label>
            <button className="primary" type="submit">Request timeouts</button>
          </ActionForm>
        </section>
      ) : null}
    </>
  );
}
