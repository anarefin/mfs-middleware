// The partner route editor (ADR D26, ticket 83; user decision 2026-11-18: a monospace textarea, no
// embedded visual editor). Authoring is done offline with Camel JBang and the mw-* Kamelets (Kaoto
// optional), then pasted here; mw-admin-api checks it (MW-VAL-041 with line and construct, shown by
// ActionForm next to the form).
import { Button } from "@/components/ui/button";
import { Field } from "@/components/ui/field";
import { Input, NativeSelect, Textarea } from "@/components/ui/input";
import type { PartnerConfig } from "@/lib/types";
import { ActionForm } from "../../action-form";
import { requestPartnerRoute } from "../../actions";

const mono = "font-mono text-xs leading-relaxed";
const json = (v: unknown) => (v === undefined ? "" : JSON.stringify(v, null, 2));

type Props = { partnerId: string; config?: PartnerConfig; newPartner?: boolean };

export function RouteForm({ partnerId, config, newPartner }: Props) {
  return (
    <ActionForm action={requestPartnerRoute.bind(null, partnerId)} className="flex flex-col gap-3" label="Partner route">
      <div className="flex flex-col gap-3">
        {newPartner ? (
          <Field label="Family" className="max-w-60">
            <NativeSelect name="family" defaultValue="billpay">
              <option value="billpay">billpay</option>
              <option value="telco">telco</option>
              <option value="bank">bank</option>
            </NativeSelect>
          </Field>
        ) : null}
        <Field label="Route (Camel YAML DSL)">
          <Textarea name="route" className={mono} rows={18} spellCheck={false} required
            defaultValue={config?.route ?? `- route:\n    from:\n      uri: direct:partner-${partnerId}\n      steps:\n        - to: "kamelet:mw-audit"\n`} />
        </Field>
        <Field label="Allowed hosts (one per line)">
          <Textarea name="allowedHosts" className={mono} rows={2} spellCheck={false} defaultValue={(config?.allowedHosts ?? []).join("\n")} />
        </Field>
        <Field label="Mappings (JSON: name -> JSONata)">
          <Textarea name="mappings" className={mono} rows={6} spellCheck={false} defaultValue={json(config?.mappings ?? {})} />
        </Field>
        <div className="grid grid-cols-[repeat(auto-fill,minmax(220px,1fr))] gap-3">
          <Field label="Dry-run operation">
            <NativeSelect name="dryRunOperation" defaultValue={config?.dryRun?.operation ?? "PAY"}>
              <option>PAY</option>
              <option>INQUIRY</option>
              <option>STATUS_QUERY</option>
            </NativeSelect>
          </Field>
          <Field label="Call timeout (ms)"><Input name="timeoutMs" inputMode="numeric" className="tabular-nums" defaultValue={config?.timeoutMs ?? ""} /></Field>
        </div>
        <Field label="Dry-run sample (JSON: the canonical call input)">
          <Textarea name="dryRunSample" className={mono} rows={4} spellCheck={false} defaultValue={json(config?.dryRun?.sample ?? {})} />
        </Field>
        <Field label={<>Error map (JSON: partner code -&gt; {"{suffix, retryable}"})</>}>
          <Textarea name="errorMap" className={mono} rows={4} spellCheck={false} defaultValue={json(config?.errorMap)} />
        </Field>
        <div className="flex flex-wrap items-end gap-3">
          <Field label="Reason" className="min-w-60 flex-1"><Input name="reason" required maxLength={500} /></Field>
          <Button type="submit">Submit route</Button>
        </div>
      </div>
    </ActionForm>
  );
}
