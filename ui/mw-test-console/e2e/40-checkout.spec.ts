import { expect, test } from "@playwright/test";

test("@checkout checkout: session, OTP + PIN payment, signed redirect and IPN", async ({ page }) => {
  await page.goto("checkout");
  const headline = page.getByTestId("headline");
  await page.getByTestId("co-create").click();
  await expect(headline).toContainText("OPEN");
  await page.getByTestId("co-otp").click();
  await expect(headline).toContainText(/OTP \d{6} sent/);
  await expect(page.getByTestId("co-otp-value")).toHaveValue(/^\d{6}$/);
  await page.getByTestId("co-confirm").click();
  await expect(headline).toContainText("result PAID");
  await page.getByTestId("co-return").click();
  await expect(headline).toContainText("status PAID");
  await expect(page.getByTestId("result")).toContainText("signature valid");
  await expect(async () => {
    await page.getByTestId("co-ipn").click();
    await expect(headline).toContainText("answered 200", { timeout: 3000 });
  }).toPass({ timeout: 60_000 });
});
