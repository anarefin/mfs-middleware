// The partner route editor (ADR D26, ticket 83; user decision 2026-11-18: a monospace textarea, no
// embedded visual editor). Authoring is done offline with Camel JBang and the mw-* Kamelets (Kaoto
// optional), then pasted here; mw-admin-api checks it (MW-VAL-041 with line and construct, shown by
// ActionForm next to the form).
import type { PartnerConfig } from "@/lib/types";
import { ActionForm } from "../../action-form";
import { requestPartnerRoute } from "../../actions";

const json = (v: unknown) => (v === undefined ? "" : JSON.stringify(v, null, 2));

type Props = { partnerId: string; config?: PartnerConfig; newPartner?: boolean };

export function RouteForm({ partnerId, config, newPartner }: Props) {
  return (
    <ActionForm action={requestPartnerRoute.bind(null, partnerId)} className="route-form" label="Partner route">
      {newPartner ? (
        <label>Family
          <select name="family" defaultValue="billpay">
            <option value="billpay">billpay</option>
            <option value="telco">telco</option>
            <option value="bank">bank</option>
          </select>
        </label>
      ) : null}
      <label>Route (Camel YAML DSL)
        <textarea name="route" className="json" rows={18} spellCheck={false} required
          defaultValue={config?.route ?? `- route:\n    from:\n      uri: direct:partner-${partnerId}\n      steps:\n        - to: "kamelet:mw-audit"\n`} />
      </label>
      <label>Allowed hosts (one per line)
        <textarea name="allowedHosts" className="json" rows={2} spellCheck={false} defaultValue={(config?.allowedHosts ?? []).join("\n")} />
      </label>
      <label>Mappings (JSON: name -&gt; JSONata)
        <textarea name="mappings" className="json" rows={6} spellCheck={false} defaultValue={json(config?.mappings ?? {})} />
      </label>
      <label>Dry-run operation
        <select name="dryRunOperation" defaultValue={config?.dryRun?.operation ?? "PAY"}>
          <option>PAY</option>
          <option>INQUIRY</option>
          <option>STATUS_QUERY</option>
        </select>
      </label>
      <label>Dry-run sample (JSON: the canonical call input)
        <textarea name="dryRunSample" className="json" rows={4} spellCheck={false} defaultValue={json(config?.dryRun?.sample ?? {})} />
      </label>
      <label>Error map (JSON: partner code -&gt; {"{suffix, retryable}"})
        <textarea name="errorMap" className="json" rows={4} spellCheck={false} defaultValue={json(config?.errorMap)} />
      </label>
      <label>Call timeout (ms)<input name="timeoutMs" inputMode="numeric" defaultValue={config?.timeoutMs ?? ""} /></label>
      <label>Reason<input name="reason" required maxLength={500} /></label>
      <button className="primary" type="submit">Submit route</button>
    </ActionForm>
  );
}
