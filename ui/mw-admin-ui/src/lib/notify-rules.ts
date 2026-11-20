// Notification rules as the console edits them (ticket 133c, FRS NOT-01): a rule sends a template
// on each of its channels to a recipient when a txn event of one type ends in one outcome group,
// for one customer type. The checks are a copy of mw-admin-api's (the API decides, 400
// MW-VAL-040), so a malformed form never reaches it.

import type { ParseResult } from "./partner-tabs";

export const TEMPLATES_TABS = [
  { id: "templates", label: "Templates" },
  { id: "rules", label: "Rules" },
] as const;

export type TemplatesTab = (typeof TEMPLATES_TABS)[number]["id"];

export function templatesTab(raw: string | undefined): TemplatesTab {
  return TEMPLATES_TABS.some((t) => t.id === raw) ? (raw as TemplatesTab) : "templates";
}

export function templatesTabHref(tab: TemplatesTab): string {
  return tab === "templates" ? "/templates" : `/templates?tab=${tab}`;
}

export const OUTCOMES = ["SUCCESS", "FAILURE", "PROCESSING"] as const;
export const CUSTOMER_TYPES = ["*", "CUSTOMER", "AGENT", "MERCHANT", "DISTRIBUTOR", "DSO", "ORG"] as const;
export const RECIPIENTS = ["INITIATOR", "DEBIT_PARTY", "CREDIT_PARTY", "OPS"] as const;
export const RULE_CHANNELS = ["SMS", "EMAIL", "PUSH"] as const;

export type Outcome = (typeof OUTCOMES)[number];
export type RuleChannel = (typeof RULE_CHANNELS)[number];

/** The statuses of mw.txn.status.v1 each outcome group matches (mw-notify NotifyRulePublisher). */
const OUTCOME_STATUSES: Record<Outcome, string> = {
  SUCCESS: "COMPLETED",
  FAILURE: "FAILED or REJECTED",
  PROCESSING: "UNKNOWN or MANUAL_REVIEW",
};

/** POST /admin/v1/notify-rules/{ruleId}/change-requests. */
export type NotifyRuleProposal = {
  eventType: string;
  outcome: Outcome;
  customerType: string;
  recipient: string;
  channels: RuleChannel[];
  templateCode: string;
  enabled: boolean;
  reason: string;
};

/** One item of GET /admin/v1/notify-rules: the live version of a rule. */
export type NotifyRule = Omit<NotifyRuleProposal, "reason"> & {
  ruleId: string;
  version: number;
  approvedBy?: string | null;
  approvedAt?: string | null;
};

const RULE_ID = /^[a-z0-9][a-z0-9-]{0,47}$/;
const EVENT_TYPE = /^[A-Z][A-Z0-9_]{1,31}$/;
const CODE = /^[A-Z][A-Z0-9_]{1,63}$/;

const fail = <T>(field: string, issue: string): ParseResult<T> => ({ ok: false, field, issue });

const oneOf = <T extends string>(list: readonly T[], v: string | undefined): v is T =>
  v !== undefined && (list as readonly string[]).includes(v);

/** The rule form (checkboxes: `channels` repeated, `enabled` present when ticked). */
export function parseRuleForm(input: {
  ruleId?: string;
  eventType?: string;
  outcome?: string;
  customerType?: string;
  recipient?: string;
  channels?: string[];
  templateCode?: string;
  enabled?: string;
  reason?: string;
}): ParseResult<{ ruleId: string; body: NotifyRuleProposal }> {
  const ruleId = input.ruleId?.trim() ?? "";
  if (!RULE_ID.test(ruleId)) return fail("ruleId", "1 to 48 lower-case letters, digits or -");
  const eventType = input.eventType?.trim() ?? "";
  if (!EVENT_TYPE.test(eventType)) return fail("eventType", "a txnType (A-Z, 0-9 or _), e.g. P2P");
  if (!oneOf(OUTCOMES, input.outcome)) return fail("outcome", "SUCCESS, FAILURE or PROCESSING");
  const customerType = input.customerType?.trim() || "*";
  if (!oneOf(CUSTOMER_TYPES, customerType)) return fail("customerType", "a wallet type, ORG or *");
  if (!oneOf(RECIPIENTS, input.recipient)) return fail("recipient", "INITIATOR, DEBIT_PARTY, CREDIT_PARTY or OPS");
  const picked = input.channels ?? [];
  if (picked.length === 0 || !picked.every((c) => oneOf(RULE_CHANNELS, c))) {
    return fail("channels", "tick SMS, EMAIL or PUSH");
  }
  const templateCode = input.templateCode?.trim() ?? "";
  if (!CODE.test(templateCode)) return fail("templateCode", "2 to 64 of A-Z, 0-9 or _");
  const reason = input.reason?.trim() ?? "";
  if (!reason || reason.length > 500) return fail("reason", "1 to 500 characters");
  return {
    ok: true,
    value: {
      ruleId,
      body: {
        eventType, outcome: input.outcome, customerType, recipient: input.recipient,
        channels: RULE_CHANNELS.filter((c) => picked.includes(c)), templateCode,
        enabled: input.enabled === "true" || input.enabled === "on", reason,
      },
    },
  };
}

/** When a rule fires, in words. */
export function ruleCondition(eventType: string, outcome: Outcome): string {
  return `${eventType}: ${OUTCOME_STATUSES[outcome]}`;
}
