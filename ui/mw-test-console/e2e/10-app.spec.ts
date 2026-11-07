import { expect, test, type Page } from "@playwright/test";

async function submit(page: Page, type: string, counterparty: string | null, amount: string, user?: string) {
  await page.getByTestId("txn-type").selectOption(type);
  if (user) await page.getByTestId("app-user").fill(user);
  if (counterparty !== null) await page.getByTestId("counterparty").fill(counterparty);
  await page.getByTestId("amount").fill(amount);
  await page.getByTestId("submit").click();
  const result = page.getByTestId("result").first();
  await expect(result.getByTestId("headline")).toBeVisible();
  return result;
}

test.describe("@app app flows", () => {
  test("P2P completes, moves the amount once, and a resend with the same key moves nothing", async ({ page }) => {
    await page.goto("app");
    const r = await submit(page, "P2P", "01711000002", "10");
    await expect(r.getByTestId("txn-status")).toHaveText("COMPLETED");
    await expect(r.getByTestId("delta-01711000001")).toHaveText("-Tk 15.00");
    await expect(r.getByTestId("delta-FEE-POOL")).toHaveText("Tk 5.00");
    await expect(r.getByTestId("delta-01711000002")).toHaveText("Tk 10.00");
    const txnId = await r.getByTestId("txn-id").textContent();

    await page.getByTestId("resend").click();
    const again = page.locator("section", { has: page.getByRole("heading", { name: "Resend" }) }).getByTestId("result");
    await expect(again.getByTestId("txn-id")).toHaveText(txnId ?? "");
    await expect(again.getByTestId("delta-01711000001")).toHaveText("Tk 0.00");
  });

  test("a wrong PIN is refused and moves nothing", async ({ page }) => {
    await page.goto("app");
    await page.getByTestId("pin").fill("9999");
    const r = await submit(page, "P2P", "01711000002", "10");
    await expect(r.getByTestId("txn-status")).toHaveText("REJECTED");
    await expect(r.getByTestId("headline")).toContainText("MW-CORE-INVALID_PIN");
    await expect(r.getByTestId("delta-01711000001")).toHaveText("Tk 0.00");
  });

  test("cash in by the agent credits the customer", async ({ page }) => {
    await page.goto("app");
    const r = await submit(page, "CASH_IN", "01711000001", "20", "agent01");
    await expect(r.getByTestId("txn-status")).toHaveText("COMPLETED");
    await expect(r.getByTestId("delta-01711000001")).toHaveText("Tk 20.00");
  });

  test("top-up through the OCS completes", async ({ page }) => {
    await page.goto("app");
    const r = await submit(page, "TOPUP", "01711000002", "20");
    await expect(r.getByTestId("txn-status")).toHaveText("COMPLETED");
    await expect(r.getByTestId("delta-01711000001")).toHaveText("-Tk 22.00");
  });

  test("bill payment completes", async ({ page }) => {
    await page.goto("app");
    const r = await submit(page, "BILL_PAY", "1234567", "100");
    await expect(r.getByTestId("txn-status")).toHaveText("COMPLETED");
  });

  test("add money from the bank credits the wallet", async ({ page }) => {
    await page.goto("app");
    const r = await submit(page, "ADD_MONEY_BANK", "1234567890123", "500");
    await expect(r.getByTestId("txn-status")).toHaveText("COMPLETED");
    await expect(r.getByTestId("delta-01711000001")).toHaveText("Tk 500.00");
  });
});
