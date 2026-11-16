import { expect, test, type Page } from "@playwright/test";

async function press(page: Page, input: string) {
  const before = await page.getByTestId("ussd-transcript").locator("li").count();
  await page.getByTestId("ussd-input").fill(input);
  await page.getByTestId("ussd-send").click();
  await expect(page.getByTestId("ussd-transcript").locator("li")).toHaveCount(before + 1);
}

for (const mno of ["robi", "gp", "bl", "teletalk"]) {
  test(`@ussd USSD ${mno}: send money in English ends with the confirmation`, async ({ page }) => {
    await page.goto("ussd");
    await page.getByTestId("ussd-mno").selectOption(mno);
    await page.getByTestId("ussd-send").click();
    await expect(page.getByTestId("ussd-screen")).toContainText("Bits");
    for (const k of ["9", "1", "01711000002", "10", "1", "13579"]) await press(page, k);
    await expect(page.getByTestId("ussd-transcript")).toContainText("****");
    await expect(page.getByTestId("ussd-transcript")).toContainText("[END]");
    await expect(page.getByTestId("ussd-transcript")).not.toContainText("you typed: 13579");
  });
}

test("@ussd USSD robi: option 5 shows the balance after the PIN", async ({ page }) => {
  await page.goto("ussd");
  await page.getByTestId("ussd-mno").selectOption("robi");
  await page.getByTestId("ussd-send").click();
  await expect(page.getByTestId("ussd-screen")).toContainText("5 ব্যালেন্স");
  for (const k of ["9", "5", "13579"]) await press(page, k);
  await expect(page.getByTestId("ussd-transcript")).toContainText("Your balance is Tk ");
  await expect(page.getByTestId("ussd-transcript")).toContainText("[END]");
  await expect(page.getByTestId("ussd-transcript")).not.toContainText("you typed: 13579");
});

test("@ussd USSD: each MNO fills in its own code", async ({ page }) => {
  await page.goto("ussd");
  for (const [mno, code] of [["gp", "*268#"], ["robi", "*269#"], ["bl", "*270#"], ["teletalk", "*271#"]]) {
    await page.getByTestId("ussd-mno").selectOption(mno);
    await expect(page.getByTestId("ussd-code")).toHaveValue(code);
  }
});

test("@ussd USSD robi: dialling gp's code ends at once with the wrong-code screen", async ({ page }) => {
  await page.goto("ussd");
  await page.getByTestId("ussd-mno").selectOption("robi");
  await page.getByTestId("ussd-code").fill("*268#");
  await page.getByTestId("ussd-send").click();
  await expect(page.getByTestId("ussd-transcript")).toContainText("[END]");
  await expect(page.getByTestId("ussd-screen")).toContainText("*269#");
  await expect(page.getByTestId("ussd-send")).toHaveText("Dial");
});

test("@ussd USSD: switching the MNO mid-session dials again without a reload", async ({ page }) => {
  await page.goto("ussd");
  await page.getByTestId("ussd-mno").selectOption("robi");
  await page.getByTestId("ussd-send").click();
  await expect(page.getByTestId("ussd-send")).toHaveText("Send");
  await page.getByTestId("ussd-mno").selectOption("gp");
  await expect(page.getByTestId("ussd-status")).toContainText("session dropped");
  await expect(page.getByTestId("ussd-send")).toHaveText("Dial");
  await page.getByTestId("ussd-send").click();
  await expect(page.getByTestId("ussd-status")).toHaveText("session open");
  await expect(page.getByTestId("ussd-screen")).toContainText("Bits");
});

test("@ussd USSD: End hangs up and Dial starts a new session", async ({ page }) => {
  await page.goto("ussd");
  await page.getByTestId("ussd-mno").selectOption("robi");
  await page.getByTestId("ussd-send").click();
  await press(page, "9");
  await page.getByTestId("ussd-end").click();
  await expect(page.getByTestId("ussd-status")).toHaveText("session ended");
  await expect(page.getByTestId("ussd-send")).toHaveText("Dial");
  await page.getByTestId("ussd-send").click();
  await expect(page.getByTestId("ussd-status")).toHaveText("session open");
  await expect(page.getByTestId("ussd-screen")).toContainText("5 ব্যালেন্স");
});
