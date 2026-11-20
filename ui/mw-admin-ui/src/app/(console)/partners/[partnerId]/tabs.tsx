import Link from "next/link";
import { AlertCircle } from "lucide-react";
import { ActionForm } from "../../action-form";
import type { ActionState } from "../../actions";
import { ApiError } from "../../api-error";
import { PendingNotice } from "../../pending-notice-view";
import { Button } from "@/components/ui/button";
import { CardContent, CardDescription, CardHeader, CardSection, CardTitle } from "@/components/ui/card";
import { Field } from "@/components/ui/field";
import { Input, NativeSelect, Textarea } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { formatTime } from "@/lib/format";
import { formatAmbiguous, formatErrorMap, PARTNER_TABS, partnerTabHref, type PartnerTab } from "@/lib/partner-tabs";
import type { ApiResult, ChangeRequest, CredentialPage, PartnerConfig } from "@/lib/types";
import { CredentialUploadForm } from "./credential-form";

const noop = async (): Promise<ActionState> => null;

export function PartnerTabNav({ partnerId, tab }: { partnerId: string; tab: PartnerTab }) {
  return (
    <nav aria-label="Partner sections" data-testid="partner-tabs" className="flex flex-wrap gap-1">
      {PARTNER_TABS.map((t) => (
        <Link
          key={t.id}
          href={partnerTabHref(partnerId, t.id)}
          aria-current={t.id === tab ? "page" : undefined}
          className={`rounded-md border px-3 py-1.5 text-sm ${t.id === tab ? "border-foreground bg-muted font-medium" : "text-muted-foreground hover:text-foreground"}`}
        >
          {t.label}
        </Link>
      ))}
    </nav>
  );
}

function WindowFacts({ config }: { config: PartnerConfig }) {
  const w = config.maintenanceWindow;
  return (
    <dl className="m-0 grid grid-cols-[repeat(auto-fill,minmax(220px,1fr))] gap-x-6 gap-y-4 [&_dd]:m-0 [&_dd]:mt-1 [&_dd]:text-sm">
      <div>
        <dt className="text-xs text-muted-foreground">In maintenance now</dt>
        <dd data-testid="maintenance-state">{config.maintenance === true ? "on" : "off"}</dd>
      </div>
      <div className="col-span-full">
        <dt className="text-xs text-muted-foreground">Window</dt>
        <dd data-testid="maintenance-window">
          {w ? (
            <>
              {w.start ? formatTime(w.start) : "now"} – {w.end ? formatTime(w.end) : "until cleared"}
              {w.messageEn ? <> · {w.messageEn}</> : null}
              {w.messageBn ? <> · {w.messageBn}</> : null}
            </>
          ) : "none"}
        </dd>
      </div>
    </dl>
  );
}

export function MaintenancePanel({
  config, maker, pending, action = noop,
}: { config: PartnerConfig; maker: boolean; pending?: ChangeRequest; action?: (prev: ActionState, form: FormData) => Promise<ActionState> }) {
  return (
    <CardSection aria-label="Maintenance">
      <CardHeader className="flex-col items-start gap-1">
        <CardTitle><AlertCircle />Maintenance</CardTitle>
        <CardDescription>Inside the window every call is refused with MW-SYS-PARTNER_MAINTENANCE. Applied after a checker approves. A window stays until a new one replaces it; set an end in the past to finish it.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <WindowFacts config={config} />
        {pending ? <PendingNotice cr={pending} /> : maker ? (
          <ActionForm action={action} label="Maintenance" className="flex flex-col gap-3">
            <div className="grid grid-cols-[repeat(auto-fill,minmax(200px,1fr))] items-end gap-3">
              <Field label="Start (UTC)"><Input name="start" type="datetime-local" defaultValue={config.maintenanceWindow?.start?.slice(0, 16) ?? ""} /></Field>
              <Field label="End (UTC)"><Input name="end" type="datetime-local" defaultValue={config.maintenanceWindow?.end?.slice(0, 16) ?? ""} /></Field>
              <Field label="Message (en)"><Input name="messageEn" maxLength={500} defaultValue={config.maintenanceWindow?.messageEn ?? ""} /></Field>
              <Field label="Message (bn)"><Input name="messageBn" maxLength={500} defaultValue={config.maintenanceWindow?.messageBn ?? ""} /></Field>
              <Field label="Reason"><Input name="reason" required maxLength={500} /></Field>
            </div>
            <div className="flex flex-wrap gap-2">
              <Button type="submit" name="maintenance" value="true">Request maintenance on</Button>
              <Button type="submit" name="maintenance" value="false" variant="outline">Request maintenance off</Button>
            </div>
          </ActionForm>
        ) : null}
      </CardContent>
    </CardSection>
  );
}

export function EndpointPanel({
  config, maker, pending, action = noop,
}: { config: PartnerConfig; maker: boolean; pending?: ChangeRequest; action?: (prev: ActionState, form: FormData) => Promise<ActionState> }) {
  return (
    <CardSection aria-label="Endpoint">
      <CardHeader className="flex-col items-start gap-1">
        <CardTitle>Endpoint (mock/real)</CardTitle>
        <CardDescription>The route is not edited. A route that calls {"{{endpoint}}"} is filled from the selected profile. Both URLs, and each host must be in the allowed hosts.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <dl className="m-0 grid grid-cols-[repeat(auto-fill,minmax(220px,1fr))] gap-x-6 gap-y-4 [&_dd]:m-0 [&_dd]:mt-1 [&_dd]:text-sm">
          <div>
            <dt className="text-xs text-muted-foreground">Profile</dt>
            <dd data-testid="endpoint-profile">{config.endpoint ?? "not set"}</dd>
          </div>
          <div className="col-span-full">
            <dt className="text-xs text-muted-foreground">URLs</dt>
            <dd className="break-all font-mono text-xs" data-testid="endpoint-urls">
              MOCK {config.endpoints?.MOCK ?? "-"} · REAL {config.endpoints?.REAL ?? "-"}
            </dd>
          </div>
        </dl>
        {pending ? <PendingNotice cr={pending} /> : maker ? (
          <ActionForm action={action} label="Endpoint" className="flex flex-col gap-3">
            <input type="hidden" name="allowedHosts" value={(config.allowedHosts ?? []).join(",")} />
            <div className="grid grid-cols-[repeat(auto-fill,minmax(220px,1fr))] items-end gap-3">
              <Field label="Profile">
                <NativeSelect name="endpoint" defaultValue={config.endpoint ?? "MOCK"}>
                  <option value="MOCK">MOCK</option>
                  <option value="REAL">REAL</option>
                </NativeSelect>
              </Field>
              <Field label="MOCK URL"><Input name="mockUrl" defaultValue={config.endpoints?.MOCK ?? ""} className="font-mono text-xs" /></Field>
              <Field label="REAL URL"><Input name="realUrl" defaultValue={config.endpoints?.REAL ?? ""} className="font-mono text-xs" /></Field>
              <Field label="Reason"><Input name="reason" required maxLength={500} /></Field>
            </div>
            <Button type="submit">Request endpoint</Button>
          </ActionForm>
        ) : null}
      </CardContent>
    </CardSection>
  );
}

export function CredentialsPanel({
  result, canUpload, action = noop,
}: { result: ApiResult<CredentialPage>; canUpload: boolean; action?: (prev: ActionState, form: FormData) => Promise<ActionState> }) {
  return (
    <CardSection aria-label="Credentials">
      <CardHeader className="flex-col items-start gap-1">
        <CardTitle>Credentials</CardTitle>
        <CardDescription>Metadata only. A secret is written to Vault on approval and is never returned.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {!result.ok ? <ApiError status={result.status} error={result.error} /> : result.data.items.length === 0 ? (
          <p className="m-0 text-sm text-muted-foreground">No credentials stored.</p>
        ) : (
          <Table data-testid="credentials">
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead><TableHead>Type</TableHead><TableHead>Kid</TableHead>
                <TableHead>Fingerprint</TableHead><TableHead>Expires</TableHead><TableHead>Days left</TableHead>
                <TableHead>Version</TableHead><TableHead>Uploaded by</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {result.data.items.map((c) => (
                <TableRow key={c.credentialId}>
                  <TableCell className="font-mono text-xs">{c.name}</TableCell>
                  <TableCell>{c.type}</TableCell>
                  <TableCell className="font-mono text-xs">{c.kid}</TableCell>
                  <TableCell className="max-w-48 truncate font-mono text-xs">{c.fingerprint ?? ""}</TableCell>
                  <TableCell className="whitespace-nowrap tabular-nums">{formatTime(c.notAfter)}</TableCell>
                  <TableCell className="tabular-nums">{c.daysLeft ?? ""}</TableCell>
                  <TableCell className="tabular-nums">v{c.version}</TableCell>
                  <TableCell className="text-muted-foreground">{c.uploadedBy}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
        {canUpload ? <CredentialUploadForm action={action} /> : (
          <p className="m-0 text-sm text-muted-foreground">An integration-maker uploads a credential; this screen shows metadata only.</p>
        )}
      </CardContent>
    </CardSection>
  );
}

export function ErrorCodesPanel({
  config, maker, pending, action = noop,
}: { config: PartnerConfig; maker: boolean; pending?: ChangeRequest; action?: (prev: ActionState, form: FormData) => Promise<ActionState> }) {
  const text = formatErrorMap(config.errorMap);
  return (
    <CardSection aria-label="Error codes">
      <CardHeader className="flex-col items-start gap-1">
        <CardTitle>Error codes</CardTitle>
        <CardDescription>One line per partner code: CODE SUFFIX, or CODE SUFFIX true when the canonical error is retryable. The request replaces the whole map.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <pre className="m-0 whitespace-pre-wrap rounded-md border bg-muted/40 p-3 font-mono text-xs" data-testid="error-map">{text || "none"}</pre>
        <p className="m-0 text-sm">Ambiguous codes: <span data-testid="ambiguous-codes">{formatAmbiguous(config.ambiguousCodes) || "none"}</span></p>
        {pending ? <PendingNotice cr={pending} /> : maker ? (
          <ActionForm action={action} label="Error codes" className="flex flex-col gap-3">
            <Field label="Error map"><Textarea name="errorMap" defaultValue={text} spellCheck={false} className="font-mono text-xs" rows={8} /></Field>
            <div className="flex flex-wrap items-end gap-3">
              <Field label="Ambiguous codes" className="min-w-60 flex-1"><Input name="ambiguousCodes" defaultValue={formatAmbiguous(config.ambiguousCodes)} /></Field>
              <Field label="Reason" className="min-w-60 flex-1"><Input name="reason" required maxLength={500} /></Field>
              <Button type="submit">Request error map</Button>
            </div>
          </ActionForm>
        ) : null}
      </CardContent>
    </CardSection>
  );
}
