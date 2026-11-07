import { expect, test, type Page } from "@playwright/test";

async function press(page: Page, input: string) {
  const before = await page.getByTestId("ussd-transcript").locator("li").count();
  await page.getByTestId("ussd-input").fill(input);
  await page.getByTestId("ussd-send").click();
  await expect(page.getByTestId("ussd-transcript").locator("li")).toHaveCount(before + 1);
}

for (const mno of ["robi", "gp"]) {
  test(`@ussd USSD ${mno}: send money in English ends with the confirmation`, async ({ page }) => {
    await page.goto("ussd");
    await page.getByTestId("ussd-mno").selectOption(mno);
    await page.getByTestId("ussd-send").click();
    await expect(page.getByTestId("ussd-screen")).toContainText("Bits");
    for (const k of ["9", "1", "01711000002", "10", "1", "1234"]) await press(page, k);
    await expect(page.getByTestId("ussd-transcript")).toContainText("****");
    await expect(page.getByTestId("ussd-transcript")).toContainText("[END]");
    await expect(page.getByTestId("ussd-transcript")).not.toContainText("you typed: 1234");
  });
}
