import { ListChecks, PenLine } from "lucide-react";
import Link from "next/link";
import { ActionForm } from "../action-form";
import type { ActionState } from "../actions";
import { PendingNotice } from "../pending-notice-view";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardSection, CardTitle } from "@/components/ui/card";
import { Field } from "@/components/ui/field";
import { Input, NativeSelect } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { formatTime } from "@/lib/format";
import {
  CUSTOMER_TYPES, OUTCOMES, RECIPIENTS, RULE_CHANNELS, ruleCondition, TEMPLATES_TABS, templatesTabHref,
  type NotifyRule, type TemplatesTab,
} from "@/lib/notify-rules";
import type { ChangeRequest } from "@/lib/types";

// The Rules tab of /templates (ticket 133c, FRS NOT-01): the live rules made in the console and a
// rule editor. A proposal goes to the approvals inbox; once an ops-checker approves it, mw-notify
// applies it without a restart. The seeded rules live in mw-notify and are not listed.

export function TemplatesTabNav({ tab }: { tab: TemplatesTab }) {
  return (
    <nav aria-label="Template sections" data-testid="templates-tabs" className="flex flex-wrap gap-1">
      {TEMPLATES_TABS.map((t) => (
        <Link
          key={t.id}
          href={templatesTabHref(t.id)}
          aria-current={t.id === tab ? "page" : undefined}
          className={`rounded-md border px-3 py-1.5 text-sm ${t.id === tab ? "border-foreground bg-muted font-medium" : "text-muted-foreground hover:text-foreground"}`}
        >
          {t.label}
        </Link>
      ))}
    </nav>
  );
}

type Props = {
  rules: NotifyRule[];
  /** The rule being edited (absent: a new rule). */
  selected: NotifyRule | undefined;
  /** The open request of the selected rule, if any. */
  open: ChangeRequest | undefined;
  /** The maker's action; null for a non-maker or while a request waits. */
  action: ((prev: ActionState, form: FormData) => Promise<ActionState>) | null;
};

export function RulesPanel({ rules, selected, open, action }: Props) {
  return (
    <>
      <Card className="overflow-hidden">
        <CardHeader><CardTitle><ListChecks />Rules</CardTitle></CardHeader>
        {rules.length === 0 ? (
          <p className="m-0 px-4 py-8 text-center text-muted-foreground">No rules made in the console yet (the seeded rules live in mw-notify).</p>
        ) : (
          <Table data-testid="notify-rules">
            <TableHeader>
              <TableRow>
                <TableHead>Rule</TableHead><TableHead>When</TableHead><TableHead>Customer type</TableHead><TableHead>Recipient</TableHead>
                <TableHead>Channels</TableHead><TableHead>Template</TableHead><TableHead>Version</TableHead><TableHead>Approved</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rules.map((r) => (
                <TableRow key={r.ruleId} data-rule={r.ruleId}>
                  <TableCell className="font-mono text-xs">
                    <Link href={`/templates?tab=rules&rule=${encodeURIComponent(r.ruleId)}`} className="underline">{r.ruleId}</Link>
                  </TableCell>
                  <TableCell>{ruleCondition(r.eventType, r.outcome)}</TableCell>
                  <TableCell>{r.customerType === "*" ? "any" : r.customerType}</TableCell>
                  <TableCell>{r.recipient}</TableCell>
                  <TableCell data-testid="rule-channels">{r.enabled ? r.channels.join(" + ") : "off"}</TableCell>
                  <TableCell className="font-mono text-xs">{r.templateCode}</TableCell>
                  <TableCell className="tabular-nums">v{r.version}</TableCell>
                  <TableCell className="whitespace-nowrap text-muted-foreground tabular-nums">{r.approvedBy ?? ""} {formatTime(r.approvedAt ?? undefined)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Card>
      {open ? <PendingNotice cr={open} /> : null}
      <RuleEditor key={`${selected?.ruleId ?? "new"}@${selected?.version ?? 0}`} rule={selected} action={action} />
    </>
  );
}

function RuleEditor({ rule, action }: { rule: NotifyRule | undefined; action: Props["action"] }) {
  const fields = (
    <div className="grid grid-cols-[repeat(auto-fill,minmax(200px,1fr))] items-end gap-3">
      <Field label="Rule id">
        <Input name="ruleId" required pattern="[a-z0-9][a-z0-9-]{0,47}" className="font-mono" defaultValue={rule?.ruleId ?? ""} readOnly={Boolean(rule)} />
      </Field>
      <Field label="Event type (txnType)">
        <Input name="eventType" required pattern="[A-Z][A-Z0-9_]{1,31}" className="font-mono" defaultValue={rule?.eventType ?? ""} placeholder="P2P" />
      </Field>
      <Field label="Outcome">
        <NativeSelect name="outcome" defaultValue={rule?.outcome ?? "SUCCESS"}>{OUTCOMES.map((o) => <option key={o}>{o}</option>)}</NativeSelect>
      </Field>
      <Field label="Customer type">
        <NativeSelect name="customerType" defaultValue={rule?.customerType ?? "*"}>
          {CUSTOMER_TYPES.map((c) => <option key={c} value={c}>{c === "*" ? "any (*)" : c}</option>)}
        </NativeSelect>
      </Field>
      <Field label="Recipient">
        <NativeSelect name="recipient" defaultValue={rule?.recipient ?? "INITIATOR"}>{RECIPIENTS.map((r) => <option key={r}>{r}</option>)}</NativeSelect>
      </Field>
      <Field label="Template code">
        <Input name="templateCode" required pattern="[A-Z][A-Z0-9_]{1,63}" className="font-mono" defaultValue={rule?.templateCode ?? ""} />
      </Field>
      <fieldset className="flex flex-wrap items-center gap-3" aria-label="Channels">
        {RULE_CHANNELS.map((c) => (
          <label key={c} className="flex items-center gap-1.5 text-sm">
            <input type="checkbox" name="channels" value={c} defaultChecked={rule ? rule.channels.includes(c) : c === "SMS"} />{c}
          </label>
        ))}
      </fieldset>
      <label className="flex items-center gap-1.5 text-sm">
        <input type="checkbox" name="enabled" value="true" defaultChecked={rule?.enabled ?? true} />Enabled
      </label>
    </div>
  );
  return (
    <CardSection aria-label="Rule editor">
      <CardHeader><CardTitle><PenLine />{rule ? <>Edit rule <span className="font-mono">{rule.ruleId}</span></> : "New rule"}</CardTitle></CardHeader>
      <CardContent className="flex flex-col gap-3">
        {rule ? <Link href="/templates?tab=rules" className="text-sm underline">New rule instead</Link> : null}
        {action ? (
          <ActionForm action={action} label="Rule proposal" className="flex flex-col gap-4">
            {fields}
            <div className="flex flex-col gap-3 border-t pt-4">
              <Field label="Reason"><Input name="reason" required maxLength={500} /></Field>
              <Button type="submit" className="self-start">Request publish</Button>
            </div>
          </ActionForm>
        ) : (
          <>
            <fieldset disabled>{fields}</fieldset>
            <p className="m-0 text-sm text-muted-foreground">Only an ops-maker proposes rules (and not while one waits for approval).</p>
          </>
        )}
      </CardContent>
    </CardSection>
  );
}
