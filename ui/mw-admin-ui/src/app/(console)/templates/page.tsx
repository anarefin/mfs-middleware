import { CircleAlert, FileText, History } from "lucide-react";
import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardHeader, CardSection, CardTitle } from "@/components/ui/card";
import { Field } from "@/components/ui/field";
import { Input, NativeSelect } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { canMake } from "@/lib/approvals";
import { formatTime } from "@/lib/format";
import { adminApi, operatorToken } from "@/lib/server/admin";
import { BASE_PATH } from "@/lib/server/env";
import { KNOWN_CODES, parseTemplateKey, type TemplateTexts } from "@/lib/templates";
import { proposeTemplate } from "../actions";
import { ApiError } from "../api-error";
import { openRequest, PendingNotice } from "../pending-notice";
import { TemplateEditor } from "./template-editor";

// Notification templates (spec 10.3, 15.2, tickets 39 / 42): one template is code + channel +
// locale. The versions made in the console, an editor (Bangla input) with a live preview on sample
// params (masked as mw-notify masks them), and the proposal as a change request.

type Props = { searchParams: Promise<Record<string, string | string[] | undefined>> };

const one = (v: string | string[] | undefined) => (typeof v === "string" ? v : undefined);

function texts(value: Record<string, unknown> | undefined): TemplateTexts {
  const s = (k: string) => (typeof value?.[k] === "string" ? (value[k] as string) : undefined);
  return { body: s("body"), subject: s("subject"), htmlBody: s("htmlBody"), senderId: s("senderId") };
}

export default async function TemplatesPage({ searchParams }: Props) {
  const sp = await searchParams;
  const code = one(sp.code);
  const channel = one(sp.channel) ?? "SMS";
  const locale = one(sp.locale) ?? "bn";
  const key = code ? parseTemplateKey(code, channel, locale) : null;
  const session = await auth();
  const token = await operatorToken();
  if (!token) redirect("/login?error=SessionExpired");

  const picker = (
    <Card>
      <form className="grid grid-cols-[repeat(auto-fill,minmax(160px,1fr))] items-end gap-3 p-4" method="get" action={`${BASE_PATH}/templates`} aria-label="Pick template">
        <Field label="Code"><Input name="code" list="template-codes" defaultValue={code ?? ""} required pattern="[A-Z][A-Z0-9_]{1,63}" className="font-mono" /></Field>
        <datalist id="template-codes">{KNOWN_CODES.map((c) => <option key={c} value={c} />)}</datalist>
        <Field label="Channel"><NativeSelect name="channel" defaultValue={channel}><option>SMS</option><option>EMAIL</option></NativeSelect></Field>
        <Field label="Locale"><NativeSelect name="locale" defaultValue={locale}><option>bn</option><option>en</option></NativeSelect></Field>
        <Button type="submit">Open</Button>
      </form>
    </Card>
  );
  if (!key) {
    return (
      <>
        <PageHeader title="Notification templates" description="Pick a template by code, channel and locale to see its versions and edit it." />
        {picker}
        {code ? (
          <Alert variant="destructive" className="error" role="alert">
            <CircleAlert />
            <AlertDescription><p>A code is A-Z, 0-9 and _ (2 to 64), channel SMS or EMAIL, locale bn or en.</p></AlertDescription>
          </Alert>
        ) : null}
      </>
    );
  }

  const api = adminApi();
  const versions = await api.listTemplateVersions(token, key);
  const items = versions.ok ? versions.data.items : [];
  const latest = items.find((v) => v.status === "PUBLISHED");
  const base = latest ? await api.getTemplateVersion(token, key, latest.version) : undefined;
  const value = base?.ok ? base.data.value : undefined;
  const resourceId = `${key.code}/${key.channel}/${key.locale}`;
  const open = await openRequest(token, "notify_template", resourceId);
  const maker = canMake(session?.roles ?? []) && !open;

  return (
    <>
      <PageHeader title={<>Template <span className="font-mono">{resourceId}</span></>} />
      {picker}
      <Card className="overflow-hidden">
        <CardHeader><CardTitle><History />Versions</CardTitle></CardHeader>
        {!versions.ok ? <div className="p-4"><ApiError status={versions.status} error={versions.error} /></div> : items.length === 0 ? (
          <p className="m-0 px-4 py-8 text-center text-muted-foreground">No versions made in the console yet (the seeded first version lives in mw-notify).</p>
        ) : (
          <Table data-testid="template-versions">
            <TableHeader><TableRow><TableHead>Version</TableHead><TableHead>Status</TableHead><TableHead>Maker</TableHead><TableHead>Checker</TableHead><TableHead>Created</TableHead><TableHead>Decided</TableHead></TableRow></TableHeader>
            <TableBody>
              {items.map((v) => (
                <TableRow key={v.version}>
                  <TableCell className="font-medium tabular-nums">v{v.version}</TableCell><TableCell><StatusBadge status={v.status} /></TableCell>
                  <TableCell className="text-muted-foreground">{v.makerId}</TableCell><TableCell className="text-muted-foreground">{v.checkerId ?? ""}</TableCell>
                  <TableCell className="whitespace-nowrap text-muted-foreground tabular-nums">{formatTime(v.createdAt)}</TableCell><TableCell className="whitespace-nowrap text-muted-foreground tabular-nums">{formatTime(v.decidedAt)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Card>
      {latest && value ? (
        <CardSection aria-label="Published text">
          <CardHeader><CardTitle><FileText />Live text (v{latest.version})</CardTitle></CardHeader>
          <div className="p-4">
            <pre className="m-0 rounded-md border bg-muted px-3 py-2.5 font-mono text-sm leading-relaxed whitespace-pre-wrap break-words" lang={key.locale} data-testid="template-live">{String(value.body ?? "")}</pre>
          </div>
        </CardSection>
      ) : null}
      {open ? <PendingNotice cr={open} /> : null}
      <TemplateEditor
        key={`${resourceId}@${latest?.version ?? 0}`}
        templateKey={key}
        initial={texts(value)}
        action={maker ? proposeTemplate.bind(null, key) : null}
      />
    </>
  );
}
