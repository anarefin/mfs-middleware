import { expect, test } from "@playwright/test";

test("@bulk bulk: the maker uploads and submits the preview, the checker approves, the batch runs", async ({ page }) => {
  await page.goto("bulk");
  const headline = page.getByTestId("headline");
  await page.getByRole("button", { name: "Generate sample" }).click();
  await expect(page.getByTestId("bulk-csv")).toHaveValue(/msisdn,amount,reference/);
  await page.getByTestId("bulk-upload").click();
  await expect(headline).toContainText(/batch \S+: PREVIEW, 5 rows \(5 valid, 0 invalid\)/);
  await expect(page.getByTestId("bulk-batch")).not.toHaveValue("");
  await expect(page.getByTestId("bulk-preview-rows").locator("li")).toHaveCount(5);
  await page.getByTestId("bulk-submit").click();
  await expect(headline).toContainText("PENDING_APPROVAL");
  await page.getByTestId("bulk-user").selectOption("org-checker01");
  await page.getByTestId("bulk-approve").click();
  await expect(headline).toContainText("approve sent");
  await expect(page.getByTestId("result")).not.toContainText("✖");
  await expect(async () => {
    await page.getByTestId("bulk-get").click();
    await expect(page.getByTestId("result")).toContainText(/"status": "COMPLETED(_WITH_EXCEPTIONS)?"/, { timeout: 3000 });
  }).toPass({ timeout: 120_000 });
});

test("@bulk bulk: invalid rows show in the preview with their codes and only valid rows are submitted", async ({ page }) => {
  await page.goto("bulk");
  const headline = page.getByTestId("headline");
  const run = Date.now().toString(36);
  await page.getByTestId("bulk-csv").fill(`msisdn,amount,reference\n01711000002,1.00,PV-${run}-1\n0171,1.00,PV-${run}-2\n`);
  await page.getByTestId("bulk-upload").click();
  await expect(headline).toContainText("PREVIEW, 2 rows (1 valid, 1 invalid)");
  await expect(page.getByTestId("bulk-preview-rows")).toContainText("line 3: invalid (MSISDN_INVALID)");
  await page.getByTestId("bulk-submit").click();
  await expect(headline).toContainText("PENDING_APPROVAL, 2 rows (1 valid, 1 invalid)");
});
