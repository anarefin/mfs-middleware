import { ArrowLeft, Settings2, SlidersHorizontal } from "lucide-react";
import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import type { ReactNode } from "react";
import { auth } from "@/auth";
import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Button } from "@/components/ui/button";
import { CardContent, CardDescription, CardHeader, CardSection, CardTitle } from "@/components/ui/card";
import { Field } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
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

function BackLink() {
  return (
    <p className="m-0">
      <Link href="/partners" className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" aria-hidden="true" />Back to partners
      </Link>
    </p>
  );
}

/** One label / value pair of the config facts grid. */
function Fact({ label, children, className }: { label: string; children: ReactNode; className?: string }) {
  return (
    <div className={className}>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      {children}
    </div>
  );
}

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
        <BackLink />
        <h1 className="m-0 font-mono text-xl font-semibold tracking-tight">{partnerId}</h1>
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
      <BackLink />
      <PageHeader title={<>Partner <span className="font-mono">{p.partnerId}</span></>} />

      <CardSection aria-label="Config">
        <CardHeader><CardTitle><Settings2 />Published config</CardTitle></CardHeader>
        <CardContent>
          <dl className="m-0 grid grid-cols-[repeat(auto-fill,minmax(220px,1fr))] gap-x-6 gap-y-4 [&_dd]:m-0 [&_dd]:mt-1 [&_dd]:text-sm">
            <Fact label="State"><dd><StatusBadge status={enabled ? "enabled" : "disabled"} data-testid="partner-state" /></dd></Fact>
            <Fact label="Family"><dd>{p.family}</dd></Fact>
            <Fact label="Version"><dd className="tabular-nums" data-testid="partner-version">{p.version === 0 ? "seed (never published)" : `v${p.version}`}</dd></Fact>
            <Fact label="Changed"><dd>{p.updatedBy}{p.approvedBy ? `, approved by ${p.approvedBy}` : ""}, {formatTime(p.updatedAt)}</dd></Fact>
            <Fact label="Base URL"><dd className="break-all font-mono text-xs">{c.baseUrl}</dd></Fact>
            <Fact label="Template"><dd>{c.template ?? "rest-json-request-reply"}</dd></Fact>
            <Fact label="Timeouts">
              <dd className="tabular-nums" data-testid="partner-timeouts">
                connect {c.connectTimeoutMs ?? "-"} ms, call {c.timeoutMs ?? "-"} ms, read {c.readTimeoutMs ?? "-"} ms
              </dd>
            </Fact>
            <Fact label="Auth"><dd>{c.auth?.scheme ?? "none"}{c.auth?.credentialsRef ? <> (secret <span className="font-mono text-xs">{c.auth.credentialsRef}</span>)</> : null}</dd></Fact>
            <Fact label="Error map"><dd>{Object.keys(c.errorMap ?? {}).length} codes</dd></Fact>
            <Fact label="Operations" className="col-span-full">
              <dd className="break-words font-mono text-xs">{Object.entries(c.operations).map(([k, o]) => `${k} ${o.method} ${o.path}`).join(", ")}</dd>
            </Fact>
          </dl>
        </CardContent>
      </CardSection>

      {open ? <PendingNotice cr={open} /> : null}
      {maker && !open ? (
        <CardSection aria-label="Change partner">
          <CardHeader className="flex-col items-start gap-1">
            <CardTitle><SlidersHorizontal />Change</CardTitle>
            <CardDescription>Applied only after a different operator (ops-checker or integration-checker) approves it.</CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-6">
            <ActionForm action={requestPartnerChange.bind(null, p.partnerId)} label={enabled ? "Disable partner" : "Enable partner"} className="flex flex-col gap-3">
              <div className="flex flex-wrap items-end gap-3">
                <Field label="Reason" className="min-w-60 flex-1"><Input name="reason" required maxLength={500} /></Field>
                <Button variant={enabled ? "outline-destructive" : "default"} type="submit" name="action" value={enabled ? "DISABLE" : "ENABLE"}>
                  {enabled ? "Request disable" : "Request enable"}
                </Button>
              </div>
            </ActionForm>
            <div className="flex flex-col gap-3 border-t pt-5">
              <h2 className="m-0 text-sm font-semibold">Timeouts</h2>
              <ActionForm action={requestPartnerChange.bind(null, p.partnerId)} className="flex flex-col gap-3" label="Partner timeouts">
                <div className="grid grid-cols-[repeat(auto-fill,minmax(170px,1fr))] items-end gap-3">
                  <input type="hidden" name="action" value="SET_TIMEOUTS" />
                  <Field label="Connect (ms)"><Input name="connectTimeoutMs" inputMode="numeric" className="tabular-nums" defaultValue={c.connectTimeoutMs ?? ""} /></Field>
                  <Field label="Call timeout (ms)"><Input name="timeoutMs" inputMode="numeric" className="tabular-nums" defaultValue={c.timeoutMs ?? ""} /></Field>
                  <Field label="Read (ms)"><Input name="readTimeoutMs" inputMode="numeric" className="tabular-nums" defaultValue={c.readTimeoutMs ?? ""} /></Field>
                  <Field label="Reason"><Input name="reason" required maxLength={500} /></Field>
                  <Button type="submit">Request timeouts</Button>
                </div>
              </ActionForm>
            </div>
          </CardContent>
        </CardSection>
      ) : null}
    </>
  );
}
