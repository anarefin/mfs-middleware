import { redirect } from "next/navigation";
import { auth } from "@/auth";
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
    <form className="panel search" method="get" action={`${BASE_PATH}/templates`} aria-label="Pick template">
      <label>Code<input name="code" list="template-codes" defaultValue={code ?? ""} required pattern="[A-Z][A-Z0-9_]{1,63}" /></label>
      <datalist id="template-codes">{KNOWN_CODES.map((c) => <option key={c} value={c} />)}</datalist>
      <label>Channel<select name="channel" defaultValue={channel}><option>SMS</option><option>EMAIL</option></select></label>
      <label>Locale<select name="locale" defaultValue={locale}><option>bn</option><option>en</option></select></label>
      <button className="primary" type="submit">Open</button>
    </form>
  );
  if (!key) {
    return (
      <>
        <h1>Notification templates</h1>
        {picker}
        {code ? <p className="error" role="alert">A code is A-Z, 0-9 and _ (2 to 64), channel SMS or EMAIL, locale bn or en.</p> : null}
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
      <h1>Template <span className="mono">{resourceId}</span></h1>
      {picker}
      <h2>Versions</h2>
      {!versions.ok ? <ApiError status={versions.status} error={versions.error} /> : items.length === 0 ? (
        <p className="hint">No versions made in the console yet (the seeded first version lives in mw-notify).</p>
      ) : (
        <table data-testid="template-versions">
          <thead><tr><th>Version</th><th>Status</th><th>Maker</th><th>Checker</th><th>Created</th><th>Decided</th></tr></thead>
          <tbody>
            {items.map((v) => (
              <tr key={v.version}>
                <td>v{v.version}</td><td className={`status ${v.status}`}>{v.status}</td>
                <td>{v.makerId}</td><td>{v.checkerId ?? ""}</td>
                <td>{formatTime(v.createdAt)}</td><td>{formatTime(v.decidedAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {latest && value ? (
        <section className="panel" aria-label="Published text">
          <h2>Live text (v{latest.version})</h2>
          <pre className="template-live" lang={key.locale} data-testid="template-live">{String(value.body ?? "")}</pre>
        </section>
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
