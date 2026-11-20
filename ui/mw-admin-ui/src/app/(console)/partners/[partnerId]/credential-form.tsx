"use client";
// Write-only credential upload (ticket 131c). The secret fields are cleared as soon as the
// submit is taken, and a refusal is shown with those values removed, so the page never echoes
// what was uploaded. The reason stays, so the operator can correct the form.

import { startTransition, useActionState, useRef, type FormEvent } from "react";
import type { ActionState } from "../../actions";
import { ApiError } from "../../api-error";
import { Button } from "@/components/ui/button";
import { Field } from "@/components/ui/field";
import { Input, NativeSelect, Textarea } from "@/components/ui/input";
import type { ErrorEnvelope } from "@/lib/types";

const SECRET_FIELDS = ["secret", "privateKeyPem", "pkcs12", "pkcs12Password"] as const;

function scrub(error: ErrorEnvelope, secrets: readonly string[]): ErrorEnvelope {
  const hide = (text: string) => secrets.reduce((acc, secret) => acc.split(secret).join("****"), text);
  return {
    ...error,
    message: hide(error.message),
    ...(error.messageBn ? { messageBn: hide(error.messageBn) } : {}),
    details: error.details.map((d) => ({ ...d, ...(d.issue ? { issue: hide(d.issue) } : {}) })),
  };
}

type Props = { action: (prev: ActionState, form: FormData) => Promise<ActionState> };

export function CredentialUploadForm({ action }: Props) {
  const secrets = useRef<string[]>([]);
  const [state, formAction, pending] = useActionState(action, null);
  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const submitter = (event.nativeEvent as SubmitEvent).submitter;
    const data = new FormData(event.currentTarget, submitter);
    secrets.current = SECRET_FIELDS.map((name) => data.get(name)).filter((v): v is string => typeof v === "string" && v.length >= 8);
    for (const el of event.currentTarget.querySelectorAll<HTMLInputElement | HTMLTextAreaElement>("[data-secret]")) el.value = "";
    startTransition(() => formAction(data));
  }
  const shown = state ? scrub(state.error, secrets.current) : null;
  return (
    <form action={formAction} onSubmit={onSubmit} className="flex flex-col gap-3" aria-label="Upload credential" aria-busy={pending}>
      <fieldset disabled={pending} className="m-0 flex min-w-0 flex-col gap-3 border-0 p-0">
        <div className="grid grid-cols-[repeat(auto-fill,minmax(200px,1fr))] items-end gap-3">
          <Field label="Name" hint="The Vault field auth.credentialsRef names"><Input name="name" required maxLength={64} autoComplete="off" /></Field>
          <Field label="Type">
            <NativeSelect name="type" defaultValue="API_KEY">
              <option value="API_KEY">API key</option>
              <option value="HMAC_SECRET">HMAC secret</option>
              <option value="CLIENT_CERT">Client certificate</option>
            </NativeSelect>
          </Field>
          <Field label="Expires (UTC, API key or HMAC)"><Input name="notAfter" type="datetime-local" /></Field>
        </div>
        <Field label="Secret" hint="API key or HMAC secret, 16 to 4096 characters. Cleared on submit and never shown again.">
          <Input name="secret" type="password" data-secret="" autoComplete="off" />
        </Field>
        <Field label="Certificate (PEM)"><Textarea name="certificatePem" spellCheck={false} className="font-mono text-xs" /></Field>
        <Field label="Private key (PEM)">
          <Textarea name="privateKeyPem" data-secret="" autoComplete="off" spellCheck={false} className="font-mono text-xs" />
        </Field>
        <div className="grid grid-cols-[repeat(auto-fill,minmax(220px,1fr))] items-end gap-3">
          <Field label="PKCS#12 (base64)"><Input name="pkcs12" type="password" data-secret="" autoComplete="off" /></Field>
          <Field label="PKCS#12 password"><Input name="pkcs12Password" type="password" data-secret="" autoComplete="off" /></Field>
        </div>
        <div className="flex flex-wrap items-end gap-3">
          <Field label="Reason" className="min-w-60 flex-1"><Input name="reason" required maxLength={500} /></Field>
          <Button type="submit">Upload credential</Button>
        </div>
      </fieldset>
      {shown ? <ApiError status={state!.status} error={shown} /> : null}
    </form>
  );
}
