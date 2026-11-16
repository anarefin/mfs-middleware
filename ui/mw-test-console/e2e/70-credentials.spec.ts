import { expect, test } from "@playwright/test";
import { freshCode, portalsGrant } from "./credentials-helpers";

// The logins and secrets page: a live TOTP code a tester can sign in with, and the latest OTP of a
// number. The sign-in is the Keycloak password grant at the edge, as the org portal's client does.

test.describe("@smoke logins and secrets", () => {
  test("org-maker01 has a live TOTP code that signs in with its password", async ({ page, request }) => {
    await page.goto("credentials");
    const row = page.getByTestId("login-org-maker01");
    await expect(row).toContainText("Mizanur Rahman");
    const password = (await page.getByTestId("password-org-maker01").textContent())!.trim();
    expect(password).not.toBe("");
    const code = await freshCode(page, "totp-org-maker01");
    const r = await portalsGrant(request, "org-maker01", password, code);
    expect(r.status(), await r.text()).toBe(200);
    expect((await r.json()).access_token).toBeTruthy();
  });

  test("every ops user, ops-*01 included, has a copyable username and a live code", async ({ page }) => {
    await page.goto("credentials");
    for (const user of ["ops-maker01", "ops-viewer01", "seed-ops-maker", "org-maker01"]) {
      await expect(page.getByTestId(`user-${user}`)).toHaveText(user);
      await expect(page.getByTestId(`copy-user-${user}`)).toBeVisible();
      await expect(page.getByTestId(`totp-${user}`)).toHaveText(/^\d{6}$/, { timeout: 20_000 });
    }
    await expect(page.getByText("enrol an authenticator")).toHaveCount(0);
  });

  test("the platform secrets and the app PIN are shown", async ({ page }) => {
    await page.goto("credentials");
    await expect(page.getByTestId("app-pin")).toHaveText("13579");
    await expect(page.getByTestId("secret-MW_E2E_API_KEY")).toBeVisible();
  });

  test("the OTP lookup shows the binding code of a fresh wallet", async ({ page }) => {
    test.setTimeout(240_000);
    await page.goto("auth");
    await page.getByTestId("auth-fresh").click();
    await expect(page.getByTestId("headline").first()).toContainText(/fresh wallet 01711000\d{3} ready/, { timeout: 120_000 });
    const msisdn = await page.getByTestId("auth-msisdn").inputValue();

    await page.goto("credentials");
    await page.getByTestId("otp-msisdn").fill(msisdn);
    await page.getByTestId("otp-lookup").click();
    await expect(page.getByTestId("otp-BIND")).toHaveText(/^\d{6}$/);
    await expect(page.getByTestId("otp-LOGIN")).toHaveText(/^\d{6}$/);
  });
});
