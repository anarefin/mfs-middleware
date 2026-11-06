import { expect, type Browser, type Page } from "@playwright/test";

import { IDP, IDP_PORT } from "./ports";

/** Signs in through the fake Keycloak realm ops (password + one-time code); lands on the search. */
export async function login(page: Page, user = "ops-viewer01") {
  await page.goto("/console/transactions");
  await expect(page).toHaveURL(/\/console\/login\?callbackUrl=/);
  await page.getByRole("button", { name: "Sign in with Keycloak" }).click();
  await expect(page).toHaveURL(new RegExp(`localhost:${IDP_PORT}/realms/ops/protocol/openid-connect/auth`));
  await page.fill("#username", user);
  await page.fill("#password", "fake-test-password");
  await page.fill("#otp", "123456");
  await page.click("#kc-login");
  await expect(page).toHaveURL(/\/console\/transactions$/);
  await expect(page.getByTestId("operator")).toContainText(user);
}

/** A signed-in operator in a browser context of their own (maker and checker side by side). */
export async function operator(browser: Browser, user: string): Promise<Page> {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  await login(page, user);
  return page;
}

/** Back to the fixtures: no change requests, transactions as in e2e/fixtures/admin-api.json. */
export async function resetStub(page: Page) {
  const res = await page.request.post(`${IDP}/__test/reset`);
  expect(res.ok()).toBe(true);
}

/** Makes the stub's change-request list answer 503 until the next resetStub. */
export async function failChangeRequestList(page: Page) {
  const res = await page.request.post(`${IDP}/__test/fail-change-request-list`);
  expect(res.ok()).toBe(true);
}
