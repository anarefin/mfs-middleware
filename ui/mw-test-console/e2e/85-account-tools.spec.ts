import { expect, test } from "@playwright/test";
import { freshCode, portalsGrant } from "./credentials-helpers";

// The account tools of the seed data page: unlock a wallet locked by wrong PINs (mw-auth test API),
// reset a portal user's password to the dev value (Keycloak admin API through /test/kc).

test.describe("@auth account tools", () => {
  test.describe.configure({ timeout: 240_000 });

  test("a wallet locked by three wrong PINs is unlocked from the tools and logs in again", async ({ page }) => {
    await page.goto("auth");
    await page.getByTestId("auth-fresh").click();
    await expect(page.getByTestId("headline").first()).toContainText("ready", { timeout: 120_000 });
    const msisdn = await page.getByTestId("auth-msisdn").inputValue();
    await page.getByTestId("auth-wrong").click();
    await expect(page.getByTestId("headline").first()).toContainText("MW-AUTH-041", { timeout: 60_000 });

    await page.goto("seed-data");
    await page.getByTestId("lookup-msisdn").fill(msisdn);
    await page.getByTestId("lookup-go").click();
    await page.getByTestId(`tool-status-${msisdn}`).click();
    await expect(page.getByTestId(`tool-result-${msisdn}`)).toContainText("LOCKED");
    await page.getByTestId(`tool-unlock-${msisdn}`).click();
    await page.getByTestId("tool-confirm").click();
    await expect(page.getByTestId(`tool-result-${msisdn}`)).toContainText("unlocked");
    await expect(page.getByTestId(`tool-result-${msisdn}`)).toContainText("not locked");

    await page.goto("auth");
    await page.getByTestId("auth-msisdn").fill(msisdn);
    await page.getByTestId("auth-login").click(); // the first PIN of the fresh wallet (48207, the form's default)
    await expect(page.getByTestId("headline").first()).toContainText("logged in", { timeout: 60_000 });
  });

  test("a portal user's password is reset to the dev value and signs in with it", async ({ page, request }) => {
    await page.goto("seed-data");
    await page.getByTestId("tool-resetPassword-sp-viewer01").click();
    await page.getByTestId("tool-confirm").click();
    const result = page.getByTestId("tool-result-sp-viewer01");
    await expect(result).toContainText("password is the dev value");
    await expect(result).not.toContainText("✖");

    const password = (await page.getByTestId("password-sp-viewer01").textContent())!.trim();
    const code = await freshCode(page, "totp-sp-viewer01");
    const r = await portalsGrant(request, "sp-viewer01", password, code);
    expect(r.status(), await r.text()).toBe(200);
  });
});
