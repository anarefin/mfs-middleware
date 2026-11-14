import { expect, test, type Page } from "@playwright/test";

// Ticket 76: mw-auth's flows from the test console. The wallet page works on fresh wallets
// (core-mock customers without an app PIN) so nothing here rebinds or locks a seeded user; the USSD
// and checkout tests use customer01 and always end with the right PIN (which resets its count).

async function headline(page: Page) {
  const h = page.getByTestId("headline").first();
  await expect(h).toBeVisible({ timeout: 120_000 });
  return h;
}

async function press(page: Page, input: string) {
  const before = await page.getByTestId("ussd-transcript").locator("li").count();
  await page.getByTestId("ussd-input").fill(input);
  await page.getByTestId("ussd-send").click();
  await expect(page.getByTestId("ussd-transcript").locator("li")).toHaveCount(before + 1);
}

test.describe("@auth wallet authentication", () => {
  test.describe.configure({ timeout: 240_000 });

  test("a fresh wallet gets its first PIN, logs in, binds a second device (cooling shown) and changes its PIN", async ({ page }) => {
    await page.goto("auth");
    await page.getByTestId("auth-fresh").click();
    await expect(await headline(page)).toContainText(/fresh wallet 01711000\d{3} ready/, { timeout: 120_000 });
    await expect(page.getByTestId("auth-msisdn")).toHaveValue(/^01711000\d{3}$/);

    await page.getByTestId("auth-login").click();
    await expect(page.getByTestId("headline").first()).toContainText(/logged in: amr [a-z,]*pin/, { timeout: 60_000 });

    await page.getByTestId("auth-second").click();
    const second = page.getByTestId("headline").first();
    await expect(second).toContainText("cooling (newDevice) until", { timeout: 60_000 });
    await expect(page.getByTestId("result")).not.toContainText("✖");

    await page.getByTestId("auth-change").click();
    await expect(page.getByTestId("headline").first()).toContainText("PIN changed", { timeout: 60_000 });
    await page.getByTestId("auth-pin").fill("61938");
    await page.getByTestId("auth-login").click();
    await expect(page.getByTestId("headline").first()).toContainText("logged in", { timeout: 60_000 });
  });

  test("a self-service PIN reset is verified by the core and sets the new PIN", async ({ page }) => {
    await page.goto("auth");
    await page.getByTestId("auth-fresh").click();
    await expect(await headline(page)).toContainText("ready", { timeout: 120_000 });
    await page.getByTestId("auth-new-pin").fill("73052");
    await page.getByTestId("auth-self-reset").click();
    await expect(page.getByTestId("headline").first()).toContainText("self-service reset", { timeout: 90_000 });
    await expect(page.getByTestId("headline").first()).toContainText("DONE");
    await page.getByTestId("auth-pin").fill("73052");
    await page.getByTestId("auth-login").click();
    await expect(page.getByTestId("headline").first()).toContainText("logged in", { timeout: 60_000 });
  });

  test("three wrong PINs lock the PIN; the right one is then refused too", async ({ page }) => {
    await page.goto("auth");
    await page.getByTestId("auth-fresh").click();
    await expect(await headline(page)).toContainText("ready", { timeout: 120_000 });
    await page.getByTestId("auth-wrong").click();
    await expect(page.getByTestId("headline").first()).toContainText("MW-AUTH-041", { timeout: 60_000 });
    await page.getByTestId("auth-login").click();
    await expect(page.getByTestId("headline").first()).toContainText("refused MW-AUTH-041", { timeout: 60_000 });
  });

  test("USSD: a wrong PIN is refused by mw-auth and asked again; the right one pays", async ({ page }) => {
    await page.goto("ussd");
    await page.getByTestId("ussd-mno").selectOption("robi");
    await page.getByTestId("ussd-send").click();
    await expect(page.getByTestId("ussd-screen")).toContainText("Bits");
    for (const k of ["9", "1", "01711000002", "10", "1", "97531"]) await press(page, k);
    await expect(page.getByTestId("ussd-screen")).toContainText("Wrong PIN. 2 tries left.");
    await press(page, "13579");
    await expect(page.getByTestId("ussd-transcript")).toContainText("[END]");
    await expect(page.getByTestId("ussd-screen")).toContainText("Tk 10.00 sent to 01711000002");
  });

  test("checkout: OTP + a wrong PIN is refused and the session stays open; a new OTP and the right PIN pay", async ({ page }) => {
    await page.goto("checkout");
    const h = page.getByTestId("headline");
    await page.getByTestId("co-create").click();
    await expect(h).toContainText("OPEN");
    await page.getByTestId("co-otp").click();
    await expect(h).toContainText(/OTP \d{6} sent/);
    await page.getByTestId("co-pin").fill("97531");
    await page.getByTestId("co-confirm").click();
    await expect(h).toContainText("refused MW-AUTH-040");
    await page.getByTestId("co-otp").click();
    await expect(h).toContainText(/OTP \d{6} sent/);
    await page.getByTestId("co-pin").fill("13579");
    await page.getByTestId("co-confirm").click();
    await expect(h).toContainText("result PAID");
  });
});
