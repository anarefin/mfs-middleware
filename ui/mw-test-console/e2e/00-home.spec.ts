import { expect, test } from "@playwright/test";

test("@smoke home: the stack is ready and the seeded wallets show", async ({ page }) => {
  await page.goto("./");
  const checks = page.getByTestId("checks");
  await expect(checks).not.toContainText("✖");
  await expect(checks.locator("tr")).toHaveCount(3);
  await expect(page.getByTestId("wallets")).toContainText("01711000001");
  await page.getByTestId("preset-reset-all").click();
  await expect(page.getByTestId("headline").first()).toContainText("Reset everything to normal: applied");
});
