// Ticket 41 (approvals inbox, reversal) and the wallet-unlock screen of ticket 52, against the real
// mw-admin-api maker-checker rules (ticket 37): the seed maker files, the seed checker decides, and
// the orchestrator applies an approved reversal. The fault case "no action buttons when the
// open-request check fails" is the vitest src/app/(console)/transactions/[txnId]/txn-actions.test.tsx.
import { expect, test } from "@playwright/test";

import { approve, asChecker, clearPending, filedRequestId, highlightedRow, pendingRow, reject } from "./support/approvals";
import { CUSTOMER_A, CUSTOMER_B } from "./support/env";
import { persona } from "./support/personas";
import { operator, signIn } from "./support/session";
import { need, readState } from "./support/state";
import { p2pOverUssd } from "./support/ussd";

const masked = (msisdn: string) => `${msisdn.slice(0, 4)}****${msisdn.slice(-3)}`;

test.describe("approvals", { tag: "@approvals" }, () => {
  test("a maker requests a reversal, the checker approves it, and the txn shows REVERSED", async ({ browser }) => {
    const { TXN_P2P } = readState();
    need(!TXN_P2P, "no TXN_P2P from 10-console");
    // first logins of maker and checker (each may wait for an unused TOTP step, e.g. right after
    // make seed used theirs) plus the orchestrator's reversal
    test.setTimeout(180_000);
    const txnId = TXN_P2P!;
    const makerName = persona("maker").username;
    const checkerName = persona("checker").username;

    const maker = await operator(browser, "maker");
    await maker.goto(`/console/transactions/${txnId}`);
    const actions = maker.getByRole("region", { name: "Actions" });
    await expect(actions.getByRole("button")).toHaveText(["Request reversal"]);
    await actions.getByLabel("Reason").fill("duplicate debit reported by the customer");
    await actions.getByRole("button", { name: "Request reversal" }).click();

    // the request waits in the inbox; nothing applied yet, and the maker has no approve button
    const id = await filedRequestId(maker);
    const mine = highlightedRow(maker);
    await expect(mine).toContainText(txnId);
    await expect(mine).toContainText("REVERSE");
    await expect(mine).toContainText("PENDING");
    await expect(mine.getByTestId("diff")).toHaveText("COMPLETED -> REVERSING");
    await expect(mine).toContainText("BDT 10.00");
    await expect(mine.getByRole("button", { name: "Approve" })).toHaveCount(0);
    await maker.goto(`/console/transactions/${txnId}`);
    await expect(maker.getByTestId("status")).toContainText("COMPLETED");
    await expect(maker.getByTestId("pending-request")).toContainText(`REVERSE requested by ${makerName}`);

    await asChecker(browser, async (checker) => {
      await checker.goto("/console/approvals");
      const row = pendingRow(checker, id);
      await expect(row).toContainText(makerName);
      await expect(row).toContainText("duplicate debit reported by the customer");
      const decided = await approve(checker, id);
      await expect(decided).toContainText(checkerName);

      await decided.getByRole("link", { name: txnId }).click();
      // the orchestrator reverses it after the approval (asynchronously)
      await expect(async () => {
        await checker.reload();
        await expect(checker.getByTestId("status")).toContainText("REVERSED", { timeout: 2_000 });
        await expect(checker.getByTestId("audit-trail")).toContainText("admin.change_request.approved", { timeout: 2_000 });
      }).toPass({ timeout: 60_000 });
      await expect(checker.getByText("Reversed by")).toBeVisible();
      await expect(checker.getByRole("region", { name: "Actions" })).toHaveCount(0);
    });
    await maker.context().close();
  });

  test("a maker sees no approve button on their own request; the checker rejects it with a reason", async ({ browser }) => {
    const txnId = await p2pOverUssd(CUSTOMER_A, CUSTOMER_B);
    const maker = await operator(browser, "maker");
    await expect(async () => {
      await maker.goto(`/console/transactions/${txnId}`);
      await expect(maker.getByTestId("status")).toContainText("COMPLETED", { timeout: 2_000 });
    }).toPass({ timeout: 60_000 });
    await maker.getByRole("region", { name: "Actions" }).getByLabel("Reason").fill("chargeback");
    await maker.getByRole("button", { name: "Request reversal" }).click();
    const id = await filedRequestId(maker);
    const own = highlightedRow(maker);
    await expect(own).toContainText(persona("maker").username);
    await expect(own.getByRole("button", { name: "Approve" })).toHaveCount(0);
    await expect(own.getByRole("button", { name: "Reject" })).toHaveCount(0);
    await expect(own).toContainText("your request: another checker decides");

    await asChecker(browser, async (checker) => {
      const decided = await reject(checker, id, "ask the customer first");
      await expect(decided).toContainText("ask the customer first");
      await expect(decided).toContainText(persona("checker").username);
    });

    // rejected: nothing applied, and the maker may ask again
    await maker.goto(`/console/transactions/${txnId}`);
    await expect(maker.getByTestId("status")).toContainText("COMPLETED");
    await expect(maker.getByRole("region", { name: "Actions" }).getByRole("button")).toHaveText(["Request reversal"]);
    await maker.context().close();
  });

  test("a maker files a wallet unlock and the checker approves it from the inbox", async ({ browser }) => {
    await clearPending(browser, `wallet ${masked(CUSTOMER_B)}`);
    const maker = await operator(browser, "maker");
    await maker.getByRole("link", { name: "Wallets" }).click();
    await maker.getByLabel("Wallet (MSISDN)").fill(`+880 ${CUSTOMER_B.slice(1, 5)}-${CUSTOMER_B.slice(5)}`);
    await maker.getByLabel("Reason").fill("customer verified at the branch");
    await maker.getByRole("button", { name: "Request unlock" }).click();
    const id = await filedRequestId(maker);
    const mine = highlightedRow(maker);
    await expect(mine).toContainText("UNLOCK");
    await expect(mine).toContainText(`wallet ${masked(CUSTOMER_B)}`);
    await expect(mine.getByTestId("diff")).toHaveText("LOCKED -> ACTIVE");
    await expect(maker.getByTestId("change-requests")).not.toContainText(CUSTOMER_B);

    // a second request for the same wallet is refused with the API's envelope
    await maker.goto("/console/wallets");
    await maker.getByLabel("Wallet (MSISDN)").fill(CUSTOMER_B);
    await maker.getByLabel("Reason").fill("again");
    await maker.getByRole("button", { name: "Request unlock" }).click();
    await expect(maker.locator(".error[role=alert]")).toContainText("MW-VAL-031");

    await asChecker(browser, async (checker) => {
      await checker.getByRole("link", { name: "Approvals" }).click();
      await approve(checker, id);
    });
    await maker.context().close();
  });

  test("a malformed wallet id is refused by the form, a viewer is offered no actions", async ({ page, browser }) => {
    const { TXN_P2P } = readState();
    need(!TXN_P2P, "no TXN_P2P from 10-console");
    await signIn(page, "maker");
    await page.goto("/console/wallets");
    await page.getByLabel("Wallet (MSISDN)").fill("01212345678");
    await page.getByLabel("Reason").fill("x");
    await page.getByRole("button", { name: "Request unlock" }).click();
    await expect(page.locator(".error[role=alert]")).toContainText("MW-VAL-001");

    const viewer = await operator(browser, "viewer");
    await viewer.goto(`/console/transactions/${TXN_P2P}`);
    await expect(viewer.getByTestId("status")).toBeVisible();
    await expect(viewer.getByRole("region", { name: "Actions" })).toHaveCount(0);
    await viewer.goto("/console/wallets");
    await expect(viewer.getByRole("button", { name: "Request unlock" })).toHaveCount(0);
    await viewer.context().close();
  });
});
