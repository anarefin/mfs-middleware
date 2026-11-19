import { expect, test, type Page } from "@playwright/test";

/** Who is signed in: the category, then the wallet (value = the app user name). */
async function signIn(page: Page, category: "customer" | "agent" | "merchant", user: string) {
  await page.getByTestId("app-category").selectOption(category);
  await page.getByTestId("app-user").selectOption(user);
}

async function submit(page: Page, type: string, counterparty: string | null, amount: string, user?: { category: "customer" | "agent"; user: string }) {
  if (user) await signIn(page, user.category, user.user);
  await page.getByTestId("txn-type").selectOption(type);
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
    // the Tk 5 fee less its 15 % VAT (Tk 0.65, VAT_PAYABLE; core-mock tariff, ticket 94)
    await expect(r.getByTestId("delta-FEE_INCOME")).toHaveText("Tk 4.35");
    await expect(r.getByTestId("delta-01711000002")).toHaveText("Tk 10.00");
    const txnId = await r.getByTestId("txn-id").textContent();

    await page.getByTestId("resend").click();
    const again = page.locator("section", { has: page.getByRole("heading", { name: "Resend" }) }).getByTestId("result");
    await expect(again.getByTestId("txn-id")).toHaveText(txnId ?? "");
    await expect(again.getByTestId("delta-01711000001")).toHaveText("Tk 0.00");
  });

  test("a wrong PIN is refused and moves nothing", async ({ page }) => {
    await page.goto("app");
    await page.getByTestId("pin").fill("97531");
    const r = await submit(page, "P2P", "01711000002", "10");
    // ticket 72: mw-auth refuses it before anything is published (no txnId)
    await expect(r.getByTestId("headline")).toContainText("MW-AUTH-040");
    await expect(r.getByTestId("delta-01711000001")).toHaveText("Tk 0.00");
  });

  test("the balance check answers the available balance and moves nothing", async ({ page }) => {
    await page.goto("app");
    await page.getByTestId("check-balance").click();
    const r = page.locator("section", { has: page.getByRole("heading", { name: "Balance check" }) }).getByTestId("result");
    await expect(r.getByTestId("headline")).toContainText("available Tk ");
  });

  test("a wrong PIN on the balance check is refused with MW-AUTH-040", async ({ page }) => {
    await page.goto("app");
    await page.getByTestId("balance-pin").fill("97531");
    await page.getByTestId("check-balance").click();
    const r = page.locator("section", { has: page.getByRole("heading", { name: "Balance check" }) }).getByTestId("result");
    await expect(r.getByTestId("headline")).toContainText("MW-AUTH-040");
  });

  test("cash in by the agent credits the customer", async ({ page }) => {
    await page.goto("app");
    const r = await submit(page, "CASH_IN", "01711000001", "20", { category: "agent", user: "agent01" });
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

  test("bill payment over SOAP completes", async ({ page }) => {
    await page.goto("app");
    await page.getByTestId("txn-type").selectOption("BILL_PAY");
    await page.getByTestId("biller-category-GAS").click();
    await page.getByTestId("biller").selectOption("KGDCL");
    await expect(page.getByTestId("biller-protocol")).toHaveText("SOAP");
    const r = await submit(page, "BILL_PAY", "1234567", "100");
    await expect(r.getByTestId("txn-status")).toHaveText("COMPLETED");
    await expect(r.getByTestId("delta-01711000001")).toHaveText("-Tk 103.00");
  });

  test("add money from the bank credits the wallet", async ({ page }) => {
    await page.goto("app");
    const r = await submit(page, "ADD_MONEY_BANK", "1234567890123", "500");
    await expect(r.getByTestId("txn-status")).toHaveText("COMPLETED");
    await expect(r.getByTestId("delta-01711000001")).toHaveText("Tk 500.00");
  });

  test("the history shows a P2P as OUT for the payer and IN for the payee", async ({ page }) => {
    await page.goto("app");
    await expect(page.getByTestId("app-user")).toHaveValue("customer01");
    const r = await submit(page, "P2P", "01711000002", "10");
    await expect(r.getByTestId("txn-status")).toHaveText("COMPLETED");
    const txnId = (await r.getByTestId("txn-id").textContent()) ?? "";
    expect(txnId).toMatch(/^[0-9a-f-]{36}$/);

    const history = page.getByTestId("history");
    await history.getByTestId("history-load").click();
    const out = history.getByTestId(`history-row-${txnId}`);
    await expect(out.getByTestId("history-direction")).toHaveText("OUT");
    await expect(out.getByTestId("history-status")).toHaveText("COMPLETED");

    await signIn(page, "customer", "customer02");
    await history.getByTestId("history-load").click();
    const inRow = history.getByTestId(`history-row-${txnId}`);
    await expect(inRow.getByTestId("history-direction")).toHaveText("IN", { timeout: 60000 });
    await expect(inRow.getByTestId("txn-link")).toHaveAttribute("href", new RegExp(`txnId=${txnId}`));
  });
});
