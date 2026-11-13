"use client";
// Template editor with a live preview (ticket 42): Bangla or English text with `{{name}}`
// placeholders, sample params the operator can change, rendered as mw-notify renders (params masked
// by name, spec 18.3), the API's checks shown as they type, and the SMS size (Bangla is UCS-2).

import { Eye, PenLine, TriangleAlert } from "lucide-react";
import { useActionState, useMemo, useState } from "react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { CardContent, CardHeader, CardSection, CardTitle } from "@/components/ui/card";
import { Field } from "@/components/ui/field";
import { Input, Textarea } from "@/components/ui/input";
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
    <div className="grid items-start gap-5 lg:grid-cols-2">
      <CardSection aria-label="Template editor">
        <CardHeader><CardTitle><PenLine />Edit</CardTitle></CardHeader>
        <CardContent>
          <form action={formAction} aria-label="Template proposal" aria-busy={pending} className="flex flex-col gap-4">
            {/* anyone may edit here to try the preview; only a maker gets the submit */}
            <fieldset disabled={pending}>
              {email ? <Field label="Subject"><Input name="subject" lang={lang} dir="auto" value={subject} onChange={(e) => setSubject(e.target.value)} /></Field> : null}
              <Field label={`Body (${lang})`}>
                <Textarea name="body" lang={lang} dir="auto" rows={5} className="text-base leading-relaxed" value={body} onChange={(e) => setBody(e.target.value)} />
              </Field>
              {email ? <Field label="HTML body (optional)"><Textarea name="htmlBody" rows={5} className="font-mono text-xs" value={htmlBody} onChange={(e) => setHtmlBody(e.target.value)} /></Field> : null}
              <Field label="Sender id" className="max-w-56"><Input name="senderId" maxLength={11} value={senderId} onChange={(e) => setSenderId(e.target.value)} placeholder="Bits" /></Field>
              {action ? (
                <div className="flex flex-col gap-4 border-t pt-4">
                  <div className="grid gap-3 sm:grid-cols-2">
                    <Field label="Valid from (UTC, empty = at approval)"><Input type="datetime-local" name="validFrom" /></Field>
                    <Field label="Reason"><Input name="reason" required maxLength={500} /></Field>
                  </div>
                  <Button type="submit" className="self-start">Request publish</Button>
                </div>
              ) : <p className="m-0 text-sm text-muted-foreground">Only an ops-maker proposes template versions (and not while one waits for approval).</p>}
            </fieldset>
            {state ? <ApiError status={state.status} error={state.error} /> : null}
          </form>
        </CardContent>
      </CardSection>

      <CardSection aria-label="Live preview">
        <CardHeader><CardTitle><Eye />Live preview</CardTitle></CardHeader>
        <CardContent className="flex flex-col gap-4">
          {names.length > 0 ? (
            <div className="grid grid-cols-[repeat(auto-fill,minmax(160px,1fr))] gap-3" aria-label="Sample params">
              {names.map((n) => (
                <Field key={n} label={`Sample ${n}`}><Input className="font-mono text-xs" value={params[n] ?? ""} onChange={(e) => setOverrides((o) => ({ ...o, [n]: e.target.value }))} /></Field>
              ))}
            </div>
          ) : <p className="m-0 text-sm text-muted-foreground">No placeholders.</p>}
          {renderedSubject ? <p className="m-0 text-sm"><strong>Subject:</strong> <span lang={lang} data-testid="preview-subject">{renderedSubject.text}</span></p> : null}
          <pre className="m-0 min-h-24 w-full max-w-sm rounded-xl bg-screen p-4 font-mono text-sm leading-relaxed whitespace-pre-wrap break-words text-screen-foreground" lang={lang} data-testid="template-preview">{rendered.text}</pre>
          <div className="flex flex-col gap-1 text-xs text-muted-foreground">
            <p className="m-0">Personal params (msisdn, counterparty, account, nid, names, pin) are masked as mw-notify masks them.</p>
            {size ? <p className="m-0 tabular-nums" data-testid="sms-size">{size.encoding}, {size.length} characters, {size.parts} SMS</p> : null}
            {rendered.missing.length > 0 ? <p className="m-0">No sample for: {rendered.missing.join(", ")}</p> : null}
          </div>
          {problems.length > 0 ? (
            <Alert variant="warning" data-testid="template-problems">
              <TriangleAlert />
              <AlertDescription><ul className="font-mono text-xs">{problems.map((p) => <li key={p}>{p}</li>)}</ul></AlertDescription>
            </Alert>
          ) : null}
        </CardContent>
      </CardSection>
    </div>
  );
}
