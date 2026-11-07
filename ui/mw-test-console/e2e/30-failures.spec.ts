import { expect, test } from "@playwright/test";

test("@failures a P2P whose core reply is lost is completed by the status inquiry", async ({ page }) => {
  await page.goto("simulators");
  await page.getByTestId("preset-core-lost-reply").click();
  await expect(page.getByTestId("headline").first()).toContainText("applied");
  await page.goto("app");
  await page.getByTestId("submit").click();
  const r = page.getByTestId("result").first();
  await expect(r.getByTestId("txn-status")).toHaveText("COMPLETED");
  await expect(r).toContainText("poll the transaction until it is final");
  await expect(r.getByTestId("delta-01711000001")).toHaveText("-Tk 15.00");
  await page.goto("simulators");
  await page.getByTestId("preset-reset-all").click();
  await expect(page.getByTestId("headline").first()).toContainText("applied");
});
