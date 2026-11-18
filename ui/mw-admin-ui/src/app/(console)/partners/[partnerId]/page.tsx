import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { auth } from "@/auth";
import { canMakePartner } from "@/lib/approvals";
import { formatTime } from "@/lib/format";
import { routeStatusLabel } from "@/lib/partner-route";
import { adminApi, operatorToken } from "@/lib/server/admin";
import { ActionForm } from "../../action-form";
import { requestPartnerChange } from "../../actions";
import { ApiError } from "../../api-error";
import { openRequest, PendingNotice } from "../../pending-notice";
import { RouteForm } from "./route-form";

// One partner (tickets 38 / 42): the published config (no secrets: credential refs only) and, for
// ops-maker / integration-maker, enable / disable and timeouts as change requests. Ticket 83: the
// route (ADR D26) with its status from the adapter (PENDING_DRY_RUN, LIVE, REJECTED and why), the
// route editor, and, for an id with no partner yet, onboarding with a route.

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
    const onboard = result.status === 404 && canMakePartner(session?.roles ?? []);
    const pending = onboard ? await openRequest(token, "partner", partnerId) : null;
    return (
      <>
        <p><Link href="/partners">Back to partners</Link></p>
        <h1 className="mono">{partnerId}</h1>
        <ApiError status={result.status} error={result.error} />
        {pending ? <PendingNotice cr={pending} /> : null}
        {onboard && !pending ? (
          <section className="panel actions" aria-label="Onboard partner">
            <h2>Onboard with a route</h2>
            <p className="hint">Write the route from a Kamelet example and run it with Camel JBang against mw-simulators first (runbook
              &quot;Onboard a biller&quot;). It goes live only after a checker approves it and its dry-run passes.</p>
            <RouteForm partnerId={partnerId} newPartner />
          </section>
        ) : null}
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
          <dt>Allowed hosts</dt><dd className="mono" data-testid="partner-hosts">{(c.allowedHosts ?? []).join(", ")}</dd>
          {c.dryRun ? (
            <><dt>Dry-run</dt><dd>{c.dryRun.operation}{c.dryRun.baseUrl ? <> against <span className="mono">{c.dryRun.baseUrl}</span></> : " against the environment's simulator"}</dd></>
          ) : null}
          <dt>Timeouts</dt>
          <dd data-testid="partner-timeouts">
            connect {c.connectTimeoutMs ?? "-"} ms, call {c.timeoutMs ?? "-"} ms, read {c.readTimeoutMs ?? "-"} ms
          </dd>
          <dt>Auth</dt><dd>{c.auth?.scheme ?? "none"}{c.auth?.credentialsRef ? <> (secret <span className="mono">{c.auth.credentialsRef}</span>)</> : null}</dd>
          <dt>Error map</dt><dd>{Object.keys(c.errorMap ?? {}).length} codes</dd>
        </dl>
      </section>

      {c.route ? (
        <section className="panel" aria-label="Route">
          <h2>Route</h2>
          <dl className="facts">
            <dt>Status</dt><dd className="status" data-testid="route-status">{routeStatusLabel(p.route)}</dd>
            {p.route?.problems.length ? (
              <><dt>Problems</dt><dd><ul data-testid="route-problems">{p.route.problems.map((x) => <li key={x}>{x}</li>)}</ul></dd></>
            ) : null}
            {p.route?.kamelets.length ? <><dt>Kamelets</dt><dd className="mono">{p.route.kamelets.join(", ")}</dd></> : null}
            {p.route?.statusAt ? <><dt>Reported</dt><dd>{formatTime(p.route.statusAt)}</dd></> : null}
          </dl>
          <pre className="mono" data-testid="route-yaml">{c.route}</pre>
        </section>
      ) : null}

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
          <h2>Route</h2>
          <p className="hint">A new route version goes live only after a checker approves it and its dry-run passes; until then the live version serves.</p>
          <RouteForm partnerId={p.partnerId} config={c} />
        </section>
      ) : null}
    </>
  );
}
