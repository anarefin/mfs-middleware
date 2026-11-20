import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import type { NotifyRule } from "@/lib/notify-rules";
import type { ChangeRequest } from "@/lib/types";
import { RulesPanel, TemplatesTabNav } from "./rules-tab";

const rule: NotifyRule = {
  ruleId: "p2p-success-receipt", version: 2, eventType: "P2P", outcome: "SUCCESS", customerType: "CUSTOMER",
  recipient: "DEBIT_PARTY", channels: ["SMS", "PUSH"], templateCode: "TXN_P2P_SENT", enabled: true,
  approvedBy: "seed-ops-checker", approvedAt: "2026-11-20T10:00:00Z",
};

const pending: ChangeRequest = {
  id: "0193a1b2-0000-7000-8000-000000000133", resourceType: "notify-rule", resourceId: "p2p-success-receipt",
  action: "PUBLISH", payload: {}, diff: { before: {}, after: {} }, makerId: "seed-ops-maker", status: "PENDING",
  reason: "push too", createdAt: "2026-11-20T01:00:00Z", expiresAt: "2026-11-23T01:00:00Z",
};

const noop = async () => null;

describe("templates page Rules tab (ticket 133c, FRS NOT-01)", () => {
  it("links the Templates and Rules tabs", () => {
    const html = renderToStaticMarkup(<TemplatesTabNav tab="rules" />);
    expect(html).toContain('href="/templates?tab=rules"');
    expect(html).toContain('href="/templates"');
    expect(html).toContain('aria-current="page"');
  });

  it("lists each live rule with its condition, channels and template, with an edit link", () => {
    const html = renderToStaticMarkup(<RulesPanel rules={[rule]} selected={undefined} open={undefined} action={null} />);
    expect(html).toContain("p2p-success-receipt");
    expect(html).toContain("P2P: COMPLETED");
    expect(html).toContain("SMS + PUSH");
    expect(html).toContain("TXN_P2P_SENT");
    expect(html).toContain('href="/templates?tab=rules&amp;rule=p2p-success-receipt"');
  });

  it("a maker editing a rule gets the form filled from the live version", () => {
    const html = renderToStaticMarkup(<RulesPanel rules={[rule]} selected={rule} open={undefined} action={noop} />);
    expect(html).toContain('value="p2p-success-receipt"');
    expect(html).toMatch(/value="PUSH"[^>]*checked|checked[^>]*value="PUSH"/);
    expect(html).not.toMatch(/value="EMAIL"[^>]*checked|checked[^>]*value="EMAIL"/);
    expect(html).toContain("Request publish");
  });

  it("an open request shows the pending note and no submit", () => {
    const html = renderToStaticMarkup(<RulesPanel rules={[rule]} selected={rule} open={pending} action={null} />);
    expect(html).toContain('data-testid="pending-request"');
    expect(html).not.toContain("Request publish");
  });
});
