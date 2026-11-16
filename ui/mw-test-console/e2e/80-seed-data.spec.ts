import { expect, test } from "@playwright/test";

// The seed data page: mw-seeder's manifest with real names, core-mock's live balances and every
// row's credentials inline.

test.describe("@smoke seed data", () => {
  test("lists seed-ops-maker by name and wallet 01711000001 with its holder, balance and PIN", async ({ page }) => {
    await page.goto("seed-data");
    await expect(page.getByTestId("manifest-missing")).toHaveCount(0);
    await expect(page.getByTestId("row-seed-ops-maker")).toContainText("Nusrat Jahan");
    await expect(page.getByTestId("totp-seed-ops-maker")).toHaveText(/^\d{6}$/, { timeout: 20_000 });

    const wallet = page.getByTestId("row-01711000001");
    await expect(wallet).toContainText("Rahim Uddin");
    await expect(page.getByTestId("balance-01711000001")).toContainText(/Tk [\d,]+\.\d{2}/);
    await expect(page.getByTestId("pin-01711000001")).toHaveText("13579");
  });

  test("every ops user has a copyable username and a live code, and no TOTP reset", async ({ page }) => {
    await page.goto("seed-data");
    for (const user of ["ops-maker01", "ops-viewer01", "ops-e2e-maker01"]) {
      await expect(page.getByTestId(`user-${user}`)).toHaveText(user);
      await expect(page.getByTestId(`copy-user-${user}`)).toBeVisible();
      await expect(page.getByTestId(`totp-${user}`)).toHaveText(/^\d{6}$/, { timeout: 20_000 });
    }
    await expect(page.getByText("Reset TOTP")).toHaveCount(0);
  });

  test("the filter narrows a table and the lookup finds a generated wallet", async ({ page }) => {
    await page.goto("seed-data");
    await page.getByTestId("filter-wallets").fill("Ayesha");
    await expect(page.getByTestId("row-01711000002")).toBeVisible();
    await expect(page.getByTestId("row-01711000001")).toHaveCount(0);

    await page.getByTestId("lookup-msisdn").fill("01711000123");
    await page.getByTestId("lookup-go").click();
    await expect(page.getByTestId("lookup-result")).toContainText(/CUSTOMER .*Tk/);
  });
});
