// Ticket 41 (approvals inbox, reversal / manual TSQ / force buttons) and the wallet-unlock screen of
// ticket 52, against the stub mw-admin-api of e2e/mock/server.mjs, which applies the maker-checker
// rules of mw-admin-api (ticket 37): ops-maker files, a different ops-checker decides.
import { expect, test, type Page } from "@playwright/test";

import { login, operator, resetStub } from "./helpers";

const P2P = "3f1c2b9e-7a4d-4c1e-9b8a-2d6f0e5a1c33";
const STUCK = "c4e5f6a7-8b9c-4d0e-9f1a-2b3c4d5e6f70";

const inboxRow = (page: Page, text: string) => page.getByTestId("change-requests").locator("tbody tr", { hasText: text });

test.beforeEach(async ({ page }) => {
  await resetStub(page);
});

test("a maker requests a reversal, another user (checker) approves it, and the txn shows REVERSED", async ({ browser }) => {
  const maker = await operator(browser, "ops-maker01");
  await maker.goto(`/console/transactions/${P2P}`);
  const actions = maker.getByRole("region", { name: "Actions" });
  await expect(actions.getByRole("button")).toHaveText(["Request reversal"]);
  await actions.getByLabel("Reason").fill("duplicate debit reported by the customer");
  await actions.getByRole("button", { name: "Request reversal" }).click();

  // the request waits in the inbox; nothing applied yet, and the maker has no approve button
  await expect(maker).toHaveURL(/\/console\/approvals\?created=/);
  const mine = inboxRow(maker, P2P);
  await expect(mine).toContainText("REVERSE");
  await expect(mine).toContainText("PENDING");
  await expect(mine.getByTestId("diff")).toHaveText("COMPLETED -> REVERSING");
  await expect(mine).toContainText("BDT 1,500.50");
  await expect(mine.getByRole("button", { name: "Approve" })).toHaveCount(0);
  await maker.goto(`/console/transactions/${P2P}`);
  await expect(maker.getByTestId("status")).toContainText("COMPLETED");
  await expect(maker.getByTestId("pending-request")).toContainText("REVERSE requested by ops-maker01");

  const checker = await operator(browser, "ops-checker01");
  await checker.goto("/console/approvals");
  const row = inboxRow(checker, P2P);
  await expect(row).toContainText("ops-maker01");
  await expect(row).toContainText("duplicate debit reported by the customer");
  await row.getByRole("button", { name: "Approve" }).click();
  await expect(checker).toHaveURL(/\/console\/approvals\?status=all&decided=/);
  const decided = inboxRow(checker, P2P);
  await expect(decided).toContainText("APPROVED");
  await expect(decided).toContainText("ops-checker01");

  await checker.getByRole("link", { name: P2P }).click();
  await expect(checker.getByTestId("status")).toContainText("REVERSED");
  await expect(checker.getByText("Reversed by")).toBeVisible();
  await expect(checker.getByTestId("audit-trail")).toContainText("admin.change_request.approved");
  await expect(checker.getByRole("region", { name: "Actions" })).toHaveCount(0);
});

test("a maker cannot see an approve button on their own request", async ({ browser }) => {
  // ops-lead01 holds ops-maker AND ops-checker: still no approve on what they filed themselves
  const lead = await operator(browser, "ops-lead01");
  await lead.goto(`/console/transactions/${P2P}`);
  await lead.getByRole("region", { name: "Actions" }).getByLabel("Reason").fill("chargeback");
  await lead.getByRole("button", { name: "Request reversal" }).click();
  await expect(lead).toHaveURL(/\/console\/approvals\?created=/);
  const own = inboxRow(lead, P2P);
  await expect(own).toContainText("ops-lead01");
  await expect(own.getByRole("button", { name: "Approve" })).toHaveCount(0);
  await expect(own.getByRole("button", { name: "Reject" })).toHaveCount(0);
  await expect(own).toContainText("your request: another checker decides");

  // a request somebody else filed: the same user may decide it
  const maker = await operator(browser, "ops-maker01");
  await maker.goto(`/console/transactions/${STUCK}`);
  const actions = maker.getByRole("region", { name: "Actions" });
  await expect(actions.getByRole("button")).toHaveText(["Request manual TSQ", "Request force commit", "Request force release"]);
  await actions.getByLabel("Reason").fill("partner says it was delivered");
  await actions.getByRole("button", { name: "Request manual TSQ" }).click();
  await expect(maker).toHaveURL(/\/console\/approvals\?created=/);

  await lead.goto("/console/approvals");
  const other = inboxRow(lead, STUCK);
  await expect(other.getByTestId("diff")).toHaveText("MANUAL_REVIEW -> MANUAL_REVIEW");
  await other.getByLabel("Rejection reason").fill("ask the partner first");
  await other.getByRole("button", { name: "Reject" }).click();
  await expect(lead).toHaveURL(/decided=/);
  await expect(inboxRow(lead, STUCK)).toContainText("REJECTED");
  await expect(inboxRow(lead, STUCK)).toContainText("ask the partner first");
});

test("an ops-maker files a wallet unlock and an ops-checker approves it from the inbox", async ({ browser }) => {
  const maker = await operator(browser, "ops-maker01");
  await maker.getByRole("link", { name: "Wallets" }).click();
  await maker.getByLabel("Wallet (MSISDN)").fill("+880 1712-345678");
  await maker.getByLabel("Reason").fill("customer verified at the branch");
  await maker.getByRole("button", { name: "Request unlock" }).click();
  await expect(maker).toHaveURL(/\/console\/approvals\?created=/);
  const mine = inboxRow(maker, "UNLOCK");
  await expect(mine).toContainText("wallet 0171****678");
  await expect(mine.getByTestId("diff")).toHaveText("LOCKED -> ACTIVE");
  await expect(maker.getByTestId("change-requests")).not.toContainText("01712345678");

  // a second request for the same wallet is refused with the API's envelope
  await maker.goto("/console/wallets");
  await maker.getByLabel("Wallet (MSISDN)").fill("01712345678");
  await maker.getByLabel("Reason").fill("again");
  await maker.getByRole("button", { name: "Request unlock" }).click();
  await expect(maker.locator(".error[role=alert]")).toContainText("MW-VAL-031");

  const checker = await operator(browser, "ops-checker01");
  await checker.getByRole("link", { name: "Approvals" }).click();
  await inboxRow(checker, "UNLOCK").getByRole("button", { name: "Approve" }).click();
  await expect(checker).toHaveURL(/decided=/);
  await expect(inboxRow(checker, "UNLOCK")).toContainText("APPROVED");
});

test("a malformed wallet id is refused by the form, a viewer is offered no actions", async ({ page }) => {
  await login(page, "ops-maker01");
  await page.goto("/console/wallets");
  await page.getByLabel("Wallet (MSISDN)").fill("01212345678");
  await page.getByLabel("Reason").fill("x");
  await page.getByRole("button", { name: "Request unlock" }).click();
  await expect(page.locator(".error[role=alert]")).toContainText("MW-VAL-001");

  const viewer = await operator(page.context().browser()!, "ops-viewer01");
  await viewer.goto(`/console/transactions/${P2P}`);
  await expect(viewer.getByRole("region", { name: "Actions" })).toHaveCount(0);
  await viewer.goto("/console/wallets");
  await expect(viewer.getByRole("button", { name: "Request unlock" })).toHaveCount(0);
});
