"use client";
// Template editor with a live preview (ticket 42): Bangla or English text with `{{name}}`
// placeholders, sample params the operator can change, rendered as mw-notify renders (params masked
// by name, spec 18.3), the API's checks shown as they type, and the SMS size (Bangla is UCS-2).

import { useActionState, useMemo, useState } from "react";
import {
  placeholderNames, renderPreview, sampleParams, smsInfo, templateProblems, type TemplateKey, type TemplateTexts,
} from "@/lib/templates";
import type { ActionState } from "../actions";
import { ApiError } from "../api-error";

type Props = {
  templateKey: TemplateKey;
  initial: TemplateTexts;
  action: ((prev: ActionState, form: FormData) => Promise<ActionState>) | null;
};

export function TemplateEditor({ templateKey, initial, action }: Props) {
  const email = templateKey.channel === "EMAIL";
  const [body, setBody] = useState(initial.body ?? "");
  const [subject, setSubject] = useState(initial.subject ?? "");
  const [htmlBody, setHtmlBody] = useState(initial.htmlBody ?? "");
  const [senderId, setSenderId] = useState(initial.senderId ?? "");
  const [overrides, setOverrides] = useState<Record<string, string>>({});
  const [state, formAction, pending] = useActionState(action ?? (async () => null), null);

  const names = useMemo(() => placeholderNames([body, email ? subject : "", email ? htmlBody : ""].join("\n")), [body, subject, htmlBody, email]);
  const params = { ...sampleParams(names), ...overrides };
  const rendered = renderPreview(body, params);
  const renderedSubject = email ? renderPreview(subject, params) : null;
  const texts: TemplateTexts = {
    body, ...(senderId ? { senderId } : {}), ...(email ? { subject, ...(htmlBody ? { htmlBody } : {}) } : {}),
  };
  const problems = templateProblems(templateKey.channel, texts);
  const size = email ? null : smsInfo(rendered.text);
  const lang = templateKey.locale;

  return (
    <div className="template-editor">
      <section className="panel" aria-label="Template editor">
        <h2>Edit</h2>
        <form action={formAction} aria-label="Template proposal" aria-busy={pending} className="template-form">
          {/* anyone may edit here to try the preview; only a maker gets the submit */}
          <fieldset disabled={pending}>
            {email ? <label>Subject<input name="subject" lang={lang} dir="auto" value={subject} onChange={(e) => setSubject(e.target.value)} /></label> : null}
            <label>Body ({lang})
              <textarea name="body" lang={lang} dir="auto" rows={5} value={body} onChange={(e) => setBody(e.target.value)} />
            </label>
            {email ? <label>HTML body (optional)<textarea name="htmlBody" rows={5} value={htmlBody} onChange={(e) => setHtmlBody(e.target.value)} /></label> : null}
            <label>Sender id<input name="senderId" maxLength={11} value={senderId} onChange={(e) => setSenderId(e.target.value)} placeholder="upay" /></label>
            {action ? (
              <>
                <label>Valid from (UTC, empty = at approval)<input type="datetime-local" name="validFrom" /></label>
                <label>Reason<input name="reason" required maxLength={500} /></label>
                <span className="buttons"><button className="primary" type="submit">Request publish</button></span>
              </>
            ) : <p className="hint">Only an ops-maker proposes template versions (and not while one waits for approval).</p>}
          </fieldset>
          {state ? <ApiError status={state.status} error={state.error} /> : null}
        </form>
      </section>

      <section className="panel" aria-label="Live preview">
        <h2>Live preview</h2>
        {names.length > 0 ? (
          <div className="search" aria-label="Sample params">
            {names.map((n) => (
              <label key={n}>Sample {n}<input value={params[n] ?? ""} onChange={(e) => setOverrides((o) => ({ ...o, [n]: e.target.value }))} /></label>
            ))}
          </div>
        ) : <p className="hint">No placeholders.</p>}
        {renderedSubject ? <p><strong>Subject:</strong> <span lang={lang} data-testid="preview-subject">{renderedSubject.text}</span></p> : null}
        <pre className="template-preview" lang={lang} data-testid="template-preview">{rendered.text}</pre>
        <p className="hint">Personal params (msisdn, counterparty, account, nid, names, pin) are masked as mw-notify masks them.</p>
        {size ? <p className="hint" data-testid="sms-size">{size.encoding}, {size.length} characters, {size.parts} SMS</p> : null}
        {rendered.missing.length > 0 ? <p className="hint">No sample for: {rendered.missing.join(", ")}</p> : null}
        {problems.length > 0 ? (
          <div className="warnings" data-testid="template-problems"><ul>{problems.map((p) => <li key={p}>{p}</li>)}</ul></div>
        ) : null}
      </section>
    </div>
  );
}
