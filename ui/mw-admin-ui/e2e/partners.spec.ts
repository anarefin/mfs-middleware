// Ticket 42: partner enable / disable and timeouts through maker-checker, against the stub
// mw-admin-api (ticket 38 rules: ops-maker / integration-maker file, a different ops-checker /
// integration-checker approves; on approval the config is published at version + 1).
import { expect, test, type Page } from "@playwright/test";

import { operator, resetStub } from "./helpers";

const inboxRow = (page: Page, text: string) => page.getByTestId("change-requests").locator("tbody tr", { hasText: text });

test.beforeEach(async ({ page }) => {
  await resetStub(page);
});

test("a maker disables a partner, an integration-checker approves, the partner shows disabled at the next version", async ({ browser }) => {
  const maker = await operator(browser, "ops-maker01");
  await maker.getByRole("link", { name: "Partners" }).click();
  const list = maker.getByTestId("partners");
  await expect(list.locator("tr", { hasText: "ocs" })).toContainText("enabled");
  await list.getByRole("link", { name: "ocs" }).click();
  await expect(maker.getByTestId("partner-state")).toHaveText("enabled");
  await expect(maker.getByTestId("partner-version")).toHaveText("v3");

  const form = maker.getByRole("form", { name: "Disable partner" });
  await form.getByLabel("Reason").fill("partner incident INC-42");
  await form.getByRole("button", { name: "Request disable" }).click();
  await expect(maker).toHaveURL(/\/console\/approvals\?created=/);
  const mine = inboxRow(maker, "partner ocs");
  await expect(mine).toContainText("DISABLE");
  await expect(mine.getByTestId("diff")).toHaveText("enabled=true -> enabled=false");
  await expect(mine.getByRole("button", { name: "Approve" })).toHaveCount(0);

  // not applied yet; the partner page shows the open request instead of the forms
  await maker.goto("/console/partners/ocs");
  await expect(maker.getByTestId("partner-state")).toHaveText("enabled");
  await expect(maker.getByTestId("pending-request")).toContainText("DISABLE requested by ops-maker01");
  await expect(maker.getByRole("region", { name: "Change partner" })).toHaveCount(0);

  const checker = await operator(browser, "ops-integration01");
  await checker.goto("/console/approvals");
  await inboxRow(checker, "partner ocs").getByRole("button", { name: "Approve" }).click();
  await expect(checker).toHaveURL(/decided=/);
  await expect(inboxRow(checker, "partner ocs")).toContainText("APPROVED");
  await expect(inboxRow(checker, "partner ocs")).toContainText("published v4");

  await checker.getByRole("link", { name: "partner ocs" }).click();
  await expect(checker.getByTestId("partner-state")).toHaveText("disabled");
  await expect(checker.getByTestId("partner-version")).toHaveText("v4");
  await checker.getByRole("link", { name: "Back to partners" }).click();
  await expect(checker.getByTestId("partners").locator("tr", { hasText: "ocs" })).toContainText("disabled");
});

test("a maker changes a partner's timeouts, an ops-checker approves, the new timeouts show", async ({ browser }) => {
  const maker = await operator(browser, "ops-integration01");
  await maker.goto("/console/partners/desco");
  await expect(maker.getByTestId("partner-timeouts")).toHaveText("connect - ms, call 8000 ms, read - ms");
  const form = maker.getByRole("form", { name: "Partner timeouts" });

  // checked as the contract does before anything is sent
  await form.getByLabel("Call timeout (ms)").fill("0");
  await form.getByLabel("Reason").fill("partner is slow at month end");
  await form.getByRole("button", { name: "Request timeouts" }).click();
  await expect(form.locator(".error[role=alert]")).toContainText("MW-VAL-001");
  await expect(form.locator(".error[role=alert]")).toContainText("timeoutMs: a whole number 1 to 120000");

  // React resets the form after an action: fill it again
  await form.getByLabel("Reason").fill("partner is slow at month end");
  await form.getByLabel("Connect (ms)").fill("2000");
  await form.getByLabel("Call timeout (ms)").fill("12000");
  await form.getByLabel("Read (ms)").fill("14000");
  await form.getByRole("button", { name: "Request timeouts" }).click();
  await expect(maker).toHaveURL(/\/console\/approvals\?created=/);
  await expect(inboxRow(maker, "partner desco").getByTestId("diff"))
    .toHaveText("timeoutMs=8000 -> connectTimeoutMs=2000, readTimeoutMs=14000, timeoutMs=12000");

  const checker = await operator(browser, "ops-checker01");
  await checker.goto("/console/approvals");
  await inboxRow(checker, "partner desco").getByRole("button", { name: "Approve" }).click();
  await expect(checker).toHaveURL(/decided=/);
  await checker.goto("/console/partners/desco");
  await expect(checker.getByTestId("partner-timeouts")).toHaveText("connect 2000 ms, call 12000 ms, read 14000 ms");
  await expect(checker.getByTestId("partner-version")).toHaveText("v1");
  // a checker files nothing
  await expect(checker.getByRole("region", { name: "Change partner" })).toHaveCount(0);
});
