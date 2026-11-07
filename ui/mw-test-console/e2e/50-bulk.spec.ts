import { expect, test } from "@playwright/test";

test("@bulk bulk: the maker uploads, the checker approves, the batch runs", async ({ page }) => {
  await page.goto("bulk");
  const headline = page.getByTestId("headline");
  await page.getByRole("button", { name: "Generate sample" }).click();
  await expect(page.getByTestId("bulk-csv")).toHaveValue(/msisdn,amount,reference/);
  await page.getByTestId("bulk-upload").click();
  await expect(headline).toContainText(/batch \S+: /);
  await expect(page.getByTestId("bulk-batch")).not.toHaveValue("");
  await page.getByTestId("bulk-user").selectOption("org-checker01");
  await page.getByTestId("bulk-approve").click();
  await expect(headline).toContainText("approve sent");
  await expect(page.getByTestId("result")).not.toContainText("✖");
  await expect(async () => {
    await page.getByTestId("bulk-get").click();
    await expect(page.getByTestId("result")).toContainText(/"status": "COMPLETED(_WITH_EXCEPTIONS)?"/, { timeout: 3000 });
  }).toPass({ timeout: 120_000 });
});
