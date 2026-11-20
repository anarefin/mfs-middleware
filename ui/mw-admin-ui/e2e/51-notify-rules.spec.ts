// Ticket 133c (FRS NOT-01): the Rules tab of /templates edits a notification rule through
// maker-checker against the real mw-admin-api; once approved, the rule is live (mw-notify applies it
// without a restart, NotifyRuleChangeIT). The rule is e2e-only: event type E2E_RULE_PAY matches no
// transaction, so no flow the API suite reads sends anything new. Each run flips the channel set, so
// the spec is idempotent on a stack that has run it before.
import { expect, test, type Page } from "@playwright/test";

import { approve, asChecker, clearPending, filedRequestId, highlightedRow, reject } from "./support/approvals";
import { operator } from "./support/session";

const RULE = "e2e-rule-pay-success";
const RULES = "/console/templates?tab=rules";
const RULE_PAGE = `${RULES}&rule=${RULE}`;

const ruleRow = (page: Page) => page.getByTestId("notify-rules").locator(`tbody tr[data-rule="${RULE}"]`);

/** The live channel set, or null when the rule has never been approved. */
async function liveChannels(page: Page): Promise<string | null> {
  await page.goto(RULES);
  if ((await page.getByTestId("notify-rules").count()) === 0 || (await ruleRow(page).count()) === 0) return null;
  return ((await ruleRow(page).getByTestId("rule-channels").textContent()) ?? "").trim();
}

async function propose(maker: Page, channels: string[], reason: string): Promise<string> {
  await maker.goto(RULE_PAGE);
  const form = maker.getByRole("form", { name: "Rule proposal" });
  // read-only once the rule is live (the page opened it for editing)
  const ruleId = form.getByLabel("Rule id");
  if (await ruleId.isEditable()) await ruleId.fill(RULE);
  await expect(ruleId).toHaveValue(RULE);
  await form.getByLabel("Event type (txnType)").fill("E2E_RULE_PAY");
  await form.getByLabel("Outcome").selectOption("SUCCESS");
  await form.getByLabel("Customer type").selectOption("CUSTOMER");
  await form.getByLabel("Recipient").selectOption("DEBIT_PARTY");
  await form.getByLabel("Template code").fill("TXN_P2P_SENT");
  for (const c of ["SMS", "EMAIL", "PUSH"]) await form.getByLabel(c, { exact: true }).setChecked(channels.includes(c));
  await form.getByLabel("Enabled").check();
  await form.getByLabel("Reason").fill(reason);
  await form.getByRole("button", { name: "Request publish" }).click();
  return filedRequestId(maker);
}

test.describe("notification rules", { tag: "@templates" }, () => {
  test("a viewer sees the Rules tab and proposes nothing", async ({ browser }) => {
    const viewer = await operator(browser, "viewer");
    await viewer.goto("/console/templates");
    await viewer.getByTestId("templates-tabs").getByRole("link", { name: "Rules" }).click();
    await expect(viewer.getByRole("heading", { name: "Notification rules" })).toBeVisible();
    await expect(viewer.getByRole("button", { name: "Request publish" })).toHaveCount(0);
    await viewer.context().close();
  });

  test("a maker proposes a channel set, the checker approves, the rule is live with it", async ({ browser }) => {
    await clearPending(browser, `notify-rule ${RULE}`);
    const maker = await operator(browser, "maker");
    const before = await liveChannels(maker);
    const next = before === "SMS + EMAIL" ? ["SMS"] : ["SMS", "EMAIL"];
    const id = await propose(maker, next, "receipt channels (e2e)");
    await expect(highlightedRow(maker)).toContainText(`notify-rule ${RULE}`);
    await expect(highlightedRow(maker)).toContainText(`E2E_RULE_PAY SUCCESS CUSTOMER -> ${next.join("+")} TXN_P2P_SENT`);

    await asChecker(browser, async (checker) => {
      const decided = await approve(checker, id);
      await expect(decided).toContainText("published v");
      await decided.getByRole("link", { name: `notify-rule ${RULE}` }).click();
      await expect(ruleRow(checker).getByTestId("rule-channels")).toHaveText(next.join(" + "));
      await expect(ruleRow(checker)).toContainText("E2E_RULE_PAY: COMPLETED");
    });
    await maker.context().close();
  });

  test("a rejected rule change is never live", async ({ browser }) => {
    await clearPending(browser, `notify-rule ${RULE}`);
    const maker = await operator(browser, "maker");
    const before = await liveChannels(maker);
    const id = await propose(maker, ["SMS", "EMAIL", "PUSH"], "all channels (e2e, rejected)");

    await asChecker(browser, async (checker) => {
      await reject(checker, id, "not agreed");
      const after = await liveChannels(checker);
      expect(after).toBe(before);
      expect(after).not.toBe("SMS + EMAIL + PUSH");
    });
    await maker.context().close();
  });
});
