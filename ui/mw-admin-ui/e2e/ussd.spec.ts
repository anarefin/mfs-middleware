// Ticket 42: USSD menu tree editor with a preview walk; the new version goes through maker-checker
// (ticket 39 rules: ops-maker proposes, a different ops-checker approves and it is published).
import { expect, test, type Page } from "@playwright/test";

import { operator, resetStub } from "./helpers";

const inboxRow = (page: Page, text: string) => page.getByTestId("change-requests").locator("tbody tr", { hasText: text });
const DONE_BN = "টাকা সফলভাবে পাঠানো হয়েছে। TxnID {txnId}";

async function walkSendMoney(page: Page, outcome = "completed") {
  const preview = page.getByRole("region", { name: "Preview" });
  await preview.getByLabel("Action outcome").selectOption(outcome);
  await preview.getByRole("button", { name: "Dial" }).click();
  for (const reply of ["1", "01812345321", "500", "1", "1234"]) {
    await preview.getByLabel("Reply", { exact: true }).fill(reply);
    await preview.getByRole("button", { name: "Send" }).click();
  }
  return preview;
}

test.beforeEach(async ({ page }) => {
  await resetStub(page);
});

test("a maker edits a Bangla text, walks it in the preview, an ops-checker approves, the new version is live", async ({ browser }) => {
  const maker = await operator(browser, "ops-maker01");
  await maker.getByRole("link", { name: "USSD menus" }).click();
  await expect(maker.getByTestId("menu-versions").locator("tr", { hasText: "v1" })).toContainText("PUBLISHED");
  await expect(maker.getByRole("region", { name: "Menu tree" })).toContainText("edited from v1 (PUBLISHED)");

  // the preview walks the published tree first (Bangla, mw-ussd's default)
  const preview = maker.getByRole("region", { name: "Preview" });
  await preview.getByRole("button", { name: "Dial" }).click();
  await expect(preview.getByTestId("ussd-screen")).toHaveText("Bits\n1 সেন্ড মানি\n9 English");
  await preview.getByLabel("Reply", { exact: true }).fill("1");
  await preview.getByRole("button", { name: "Send" }).click();
  await preview.getByLabel("Reply", { exact: true }).fill("01712345678"); // own number: notSelf
  await preview.getByRole("button", { name: "Send" }).click();
  await expect(preview.getByTestId("ussd-screen")).toHaveText("সঠিক মোবাইল নম্বর দিন।\nপ্রাপকের Bits নম্বর দিন");

  // edit the success screen in Bangla and walk to it
  await maker.getByTestId("menu-tree").getByRole("button", { name: /^p2p\.done/ }).click();
  await maker.getByLabel("Text (bn)").fill(DONE_BN);
  await walkSendMoney(maker);
  await expect(preview.getByTestId("ussd-screen")).toHaveText("টাকা সফলভাবে পাঠানো হয়েছে। TxnID PREVIEW-TXN");
  await expect(preview.getByTestId("ussd-ended")).toBeVisible();
  // the PIN never shows, not even in the trail
  await expect(preview.locator("ol.trail li")).toHaveText(["(dial)", "1", "01812345321", "500", "1", "****"]);
  await expect(maker.getByTestId("menu-problems")).toContainText("No problems found");

  // a text without English is flagged before it is filed
  await maker.getByLabel("Text (en)").fill("");
  await expect(maker.getByTestId("menu-problems")).toContainText("nodes.p2p.done.text: needs bn and en");
  await maker.getByLabel("Text (en)").fill("Money sent. TxnID {txnId}");

  const propose = maker.getByRole("form", { name: "Menu proposal" });
  await propose.getByLabel("teletalk", { exact: true }).uncheck();
  await propose.getByLabel("Go-live gp").fill("2030-01-15T06:00");
  await propose.getByLabel("Reason").fill("clearer success text");
  await propose.getByRole("button", { name: "Request publish" }).click();
  await expect(maker).toHaveURL(/\/console\/approvals\?created=/);
  const mine = inboxRow(maker, "ussd_menu main");
  await expect(mine).toContainText("PUBLISH");
  await expect(mine.getByTestId("diff")).toHaveText("v1 -> v2");
  await expect(mine.getByRole("button", { name: "Approve" })).toHaveCount(0);

  // while it waits: shown as PENDING, a second proposal is not offered
  await maker.goto("/console/ussd?menuId=main");
  await expect(maker.getByTestId("menu-versions").locator("tr", { hasText: "v2" })).toContainText("PENDING");
  await expect(maker.getByTestId("pending-request")).toContainText("PUBLISH requested by ops-maker01");
  await expect(maker.getByRole("form", { name: "Menu proposal" })).toHaveCount(0);

  const checker = await operator(browser, "ops-checker01");
  await checker.goto("/console/approvals");
  await inboxRow(checker, "ussd_menu main").getByRole("button", { name: "Approve" }).click();
  await expect(checker).toHaveURL(/decided=/);
  await expect(inboxRow(checker, "ussd_menu main")).toContainText("published v2");

  // effect: v2 is published with its schedule and the editor and preview start from it
  await checker.getByRole("link", { name: "ussd_menu main" }).click();
  const v2 = checker.getByTestId("menu-versions").locator("tr", { hasText: "v2" });
  await expect(v2).toContainText("PUBLISHED");
  await expect(v2).toContainText("gp 2030-01-15 06:00:00 UTC");
  await expect(v2).not.toContainText("teletalk");
  await expect(checker.getByRole("region", { name: "Menu tree" })).toContainText("edited from v2 (PUBLISHED)");
  await walkSendMoney(checker);
  await expect(checker.getByTestId("ussd-screen")).toHaveText("টাকা সফলভাবে পাঠানো হয়েছে। TxnID PREVIEW-TXN");
  // a checker proposes nothing
  await expect(checker.getByRole("form", { name: "Menu proposal" })).toHaveCount(0);
});

test("a broken tree pasted as JSON is refused with the API's details", async ({ browser }) => {
  const maker = await operator(browser, "ops-maker01");
  await maker.goto("/console/ussd");
  await maker.getByText("JSON", { exact: true }).click();
  const json = maker.getByLabel("Menu JSON");
  const tree = JSON.parse(await json.inputValue());
  tree.nodes["p2p.amount"].next = "nowhere";
  await json.fill(JSON.stringify(tree));
  await maker.getByRole("button", { name: "Apply JSON" }).click();
  await expect(maker.getByTestId("menu-problems")).toContainText("nodes.p2p.amount.next: no node nowhere");

  const propose = maker.getByRole("form", { name: "Menu proposal" });
  await propose.getByLabel("Reason").fill("try it");
  await propose.getByRole("button", { name: "Request publish" }).click();
  await expect(propose.locator(".error[role=alert]")).toContainText("MW-VAL-040");
  await expect(propose.locator(".error[role=alert]")).toContainText("menu.nodes.p2p.amount.next: no node nowhere");
});
