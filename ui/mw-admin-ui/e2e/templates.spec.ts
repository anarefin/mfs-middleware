// Ticket 42: template editor with Bangla input and a live preview on sample params (masked as
// mw-notify masks them); the new version goes through maker-checker (ticket 39 rules).
import { expect, test, type Page } from "@playwright/test";

import { operator, resetStub } from "./helpers";

const inboxRow = (page: Page, text: string) => page.getByTestId("change-requests").locator("tbody tr", { hasText: text });
const KEY = "TXN_CASH_OUT_SENT/SMS/bn";
const NEW_BODY = "{{counterparty}} এজেন্টের কাছে {{amount}} টাকা ক্যাশ আউট হয়েছে। TxnID {{txnId}}";

test.beforeEach(async ({ page }) => {
  await resetStub(page);
});

test("the preview renders Bangla text with sample params, masked", async ({ browser }) => {
  const viewer = await operator(browser, "ops-viewer01");
  await viewer.goto("/console/templates?code=TXN_CASH_OUT_SENT&channel=SMS&locale=bn");
  const preview = viewer.getByTestId("template-preview");
  // the live text loaded into the editor, rendered with the sample params
  await expect(preview).toHaveText("এজেন্ট 0181****321-এর কাছে 1500.00 টাকা ক্যাশ আউট সফল। TxnID 0193a1b2-0000-7000-8000-000000000001");
  await expect(preview).toHaveAttribute("lang", "bn");

  await viewer.getByLabel("Body (bn)").fill("প্রাপক {{receiverName}} ({{msisdn}}), হিসাব {{account}}, পিন {{pin}}: {{amount}} টাকা");
  await viewer.getByLabel("Sample msisdn").fill("+8801999888777");
  await expect(preview).toHaveText("প্রাপক K. (+880199****777), হিসাব ****0123, পিন ****: 1500.00 টাকা");
  await expect(preview).not.toContainText("01999888777");
  await expect(viewer.getByTestId("sms-size")).toContainText("UCS-2");

  await viewer.getByLabel("Body (bn)").fill("{{#amount}}x");
  await expect(viewer.getByTestId("template-problems")).toContainText("body: placeholder {{#amount}} is not a plain {{name}}");
  // a viewer proposes nothing
  await expect(viewer.getByRole("button", { name: "Request publish" })).toHaveCount(0);
});

test("a maker proposes a Bangla text, an ops-checker approves, the new version is live", async ({ browser }) => {
  const maker = await operator(browser, "ops-maker01");
  await maker.getByRole("link", { name: "Templates" }).click();
  const picker = maker.getByRole("form", { name: "Pick template" });
  await picker.getByLabel("Code").fill("TXN_CASH_OUT_SENT");
  await picker.getByLabel("Channel").selectOption("SMS");
  await picker.getByLabel("Locale").selectOption("bn");
  await picker.getByRole("button", { name: "Open" }).click();
  await expect(maker.getByTestId("template-versions").locator("tr", { hasText: "v1" })).toContainText("PUBLISHED");

  await maker.getByLabel("Body (bn)").fill(NEW_BODY);
  await expect(maker.getByTestId("template-preview"))
    .toHaveText("0181****321 এজেন্টের কাছে 1500.00 টাকা ক্যাশ আউট হয়েছে। TxnID 0193a1b2-0000-7000-8000-000000000001");
  const form = maker.getByRole("form", { name: "Template proposal" });
  await form.getByLabel("Reason").fill("shorter wording");
  await form.getByRole("button", { name: "Request publish" }).click();
  await expect(maker).toHaveURL(/\/console\/approvals\?created=/);
  const mine = inboxRow(maker, `notify_template ${KEY}`);
  await expect(mine.getByTestId("diff")).toHaveText("v1 -> v2");
  await expect(mine).toContainText(NEW_BODY);

  const checker = await operator(browser, "ops-checker01");
  await checker.goto("/console/approvals");
  await inboxRow(checker, `notify_template ${KEY}`).getByRole("button", { name: "Approve" }).click();
  await expect(checker).toHaveURL(/decided=/);
  await expect(inboxRow(checker, `notify_template ${KEY}`)).toContainText("published v2");

  await checker.getByRole("link", { name: `notify_template ${KEY}` }).click();
  await expect(checker.getByTestId("template-versions").locator("tr", { hasText: "v2" })).toContainText("PUBLISHED");
  await expect(checker.getByTestId("template-live")).toHaveText(NEW_BODY);
  await expect(checker.getByRole("region", { name: "Published text" })).toContainText("v2");
});

test("a rejected version is never live", async ({ browser }) => {
  const maker = await operator(browser, "ops-maker01");
  await maker.goto("/console/templates?code=TXN_CASH_OUT_SENT&channel=SMS&locale=bn");
  await maker.getByLabel("Body (bn)").fill("ভুল {{amount}}");
  const form = maker.getByRole("form", { name: "Template proposal" });
  await form.getByLabel("Reason").fill("test");
  await form.getByRole("button", { name: "Request publish" }).click();
  await expect(maker).toHaveURL(/created=/);

  const checker = await operator(browser, "ops-checker01");
  await checker.goto("/console/approvals");
  const row = inboxRow(checker, `notify_template ${KEY}`);
  await row.getByLabel("Rejection reason").fill("typo");
  await row.getByRole("button", { name: "Reject" }).click();
  await expect(checker).toHaveURL(/decided=/);
  await checker.goto("/console/templates?code=TXN_CASH_OUT_SENT&channel=SMS&locale=bn");
  await expect(checker.getByTestId("template-versions").locator("tr", { hasText: "v2" })).toContainText("REJECTED");
  await expect(checker.getByTestId("template-live")).toContainText("ক্যাশ আউট সফল");
});
