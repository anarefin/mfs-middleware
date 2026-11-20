import { expect, test } from "@playwright/test";

// The transaction explorer (/tester/transactions): a P2P from App flows, its txnId link opens it
// with the status, the saga timeline and the audit trail (mw-admin-api as ops-e2e-maker01).
test.describe("@transactions transaction explorer", () => {
  test("a P2P from the app page opens with its timeline and audit trail", async ({ page }) => {
    await page.goto("app");
    await page.getByTestId("txn-type").selectOption("P2P");
    await page.getByTestId("counterparty").fill("01711000002");
    await page.getByTestId("amount").fill("10");
    await page.getByTestId("preview").click();
    await expect(page.getByTestId("preview-quote")).toBeVisible();
    await page.getByTestId("submit").click();
    const r = page.getByTestId("result").first();
    await expect(r.getByTestId("txn-status")).toHaveText("COMPLETED");
    const txnId = (await r.getByTestId("txn-id").textContent())?.trim() ?? "";
    expect(txnId).toMatch(/^[0-9a-f-]{36}$/);

    await r.getByTestId("txn-link").click();
    await expect(page).toHaveURL(new RegExp(`/tester/transactions\\?txnId=${txnId}`));
    const detail = page.getByTestId("txn-detail");
    await expect(detail.getByTestId("detail-status")).toHaveText("COMPLETED");
    await expect(detail).toContainText(txnId);
    await expect(detail).toContainText("P2P");
    await expect(page.getByTestId("txn-timeline").locator("li").first()).toBeVisible();
    expect(await page.getByTestId("txn-timeline").locator("li").count()).toBeGreaterThan(1);
    await expect(detail).toContainText("No partner calls.");
    // mw-audit-dw records the steps a few seconds later: open it again until the trail is there
    await expect(async () => {
      await page.getByTestId("tx-search").click();
      await expect(page.getByTestId("txn-audit").locator("tbody tr").first()).toBeVisible({ timeout: 5_000 });
    }).toPass({ timeout: 60_000 });
  });

  test("a search by MSISDN lists masked numbers; a bad txnId is refused before anything is sent", async ({ page }) => {
    await page.goto("transactions");
    await page.getByTestId("tx-msisdn").fill("01711000001");
    await page.getByTestId("tx-search").click();
    await expect(page.getByTestId("tx-results")).toContainText("0171****001");
    await page.getByTestId("tx-open").first().click();
    await expect(page.getByTestId("txn-detail")).toBeVisible();

    await page.getByTestId("tx-msisdn").fill("");
    await page.getByTestId("tx-txnid").fill("abc");
    await page.getByTestId("tx-search").click();
    await expect(page.getByTestId("headline")).toContainText("the txnId must be a UUID");
  });
});
