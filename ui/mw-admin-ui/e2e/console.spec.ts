import { expect, test, type Page } from "@playwright/test";

import { login } from "./helpers";
import { IDP } from "./ports";

const P2P = "3f1c2b9e-7a4d-4c1e-9b8a-2d6f0e5a1c33";
const TOPUP = "8b2d4e61-1f3a-4b7c-a9d0-5e6f7a8b9c0d";

type MockState = {
  issuedAccessTokens: string[];
  pkceVerified: number;
  apiCalls: { path: string; bearer: string }[];
};

async function mockState(page: Page): Promise<MockState> {
  return (await page.request.get(`${IDP}/__test/state`)).json() as Promise<MockState>;
}

test("an operator signs in, finds a transaction by txnId and by MSISDN and reads its timeline", async ({ page }) => {
  const cspViolations: string[] = [];
  page.on("console", (m) => {
    if (m.text().includes("Content Security Policy") && page.url().includes("/console/")) cspViolations.push(m.text());
  });
  await login(page);

  // by txnId
  await page.getByLabel("Transaction ID").fill(P2P);
  await page.getByRole("button", { name: "Search" }).click();
  const rows = page.getByTestId("results").locator("tbody tr");
  await expect(rows).toHaveCount(1);
  await expect(rows.first()).toContainText(P2P);
  await expect(rows.first()).toContainText("COMPLETED");
  await expect(rows.first()).toContainText("BDT 1,500.50");

  // by MSISDN, in the +880 form with spaces
  await page.getByLabel("Transaction ID").fill("");
  await page.getByLabel("MSISDN").fill("+880 1712-345678");
  await page.getByRole("button", { name: "Search" }).click();
  await expect(rows).toHaveCount(2);
  await expect(page.getByTestId("results")).toContainText(TOPUP);
  // masked as the admin API returns it, never the number searched for
  await expect(page.getByTestId("results")).toContainText("0171****678");
  await expect(page.getByTestId("results")).not.toContainText("01712345678");

  // detail with saga timeline, partner calls and audit trail
  await page.getByRole("link", { name: TOPUP }).click();
  await expect(page).toHaveURL(new RegExp(`/console/transactions/${TOPUP}$`));
  await expect(page.getByTestId("status")).toContainText("FAILED");
  const timeline = page.getByTestId("timeline").locator("li");
  await expect(timeline).toHaveCount(7);
  await expect(timeline.nth(3)).toContainText("HOLDING -> PARTNER_PENDING on FundsHeld");
  await expect(timeline.nth(5)).toContainText("core ReleaseFunds, sent 2x -> FundsReleased");
  await expect(page.getByTestId("partner-calls")).toContainText("MW-PTR-GP-REJECTED");

  await page.goto(`/console/transactions/${P2P}`);
  await expect(page.getByTestId("timeline").locator("li").last()).toContainText("POSTING -> COMPLETED");
  await expect(page.getByTestId("audit-trail")).toContainText("txn.completed");
  // a viewer is not offered the reveal
  await expect(page.getByText("Reveal MSISDN")).toHaveCount(0);

  // an unknown transaction shows the admin API's envelope
  await page.goto("/console/transactions/00000000-0000-4000-8000-000000000000");
  await expect(page.locator(".error[role=alert]")).toContainText("MW-VAL-003");

  // the nonce CSP let every script of the console run
  expect(cspViolations).toEqual([]);
});

test("the browser never receives the access token", async ({ page, context }) => {
  const seen: string[] = [];
  page.on("request", (r) => {
    seen.push(r.url(), JSON.stringify(r.headers()), r.postData() ?? "");
  });
  page.on("response", async (r) => {
    seen.push(JSON.stringify(await r.allHeaders()));
    try {
      seen.push(await r.text());
    } catch {
      // redirects have no body
    }
  });

  await login(page);
  await page.getByLabel("MSISDN").fill("01712345678");
  await page.getByRole("button", { name: "Search" }).click();
  await page.getByRole("link", { name: P2P }).click();
  await expect(page.getByTestId("timeline")).toBeVisible();

  // what scripts in the page can read
  seen.push(await page.evaluate(() => document.cookie));
  seen.push(await page.evaluate(() => JSON.stringify({ ...localStorage })));
  seen.push(await page.evaluate(() => JSON.stringify({ ...sessionStorage })));
  seen.push(await page.content());
  // the session endpoint the browser can call
  const session = await page.request.get("/console/api/auth/session");
  const sessionBody = await session.text();
  seen.push(sessionBody);
  expect(JSON.parse(sessionBody)).toMatchObject({ user: { name: "ops-viewer01" }, roles: ["viewer"] });

  const state = await mockState(page);
  expect(state.pkceVerified).toBeGreaterThan(0);
  expect(state.issuedAccessTokens.length).toBeGreaterThan(0);
  // the console did use the token: server-side, against the admin API
  const used = state.apiCalls.map((c) => c.bearer);
  expect(used.some((b) => state.issuedAccessTokens.includes(b))).toBe(true);

  const all = seen.join("\n");
  for (const token of state.issuedAccessTokens) {
    expect(all.includes(token), "access token reached the browser").toBe(false);
    // not even its signature part
    expect(all.includes(token.split(".")[2] ?? "")).toBe(false);
  }

  // the session cookie is HTTP-only and encrypted (a JWE, not the token)
  const cookies = await context.cookies();
  const sessionCookies = cookies.filter((c) => c.name.includes("authjs.session-token"));
  expect(sessionCookies.length).toBeGreaterThan(0);
  for (const c of sessionCookies) {
    expect(c.httpOnly).toBe(true);
    expect(c.sameSite).toBe("Lax");
  }
});

test("pages carry CSP, HSTS and X-Frame-Options DENY", async ({ page }) => {
  const res = await page.goto("/console/login");
  const h = res!.headers();
  expect(h["content-security-policy"]).toMatch(/script-src 'self' 'nonce-[^']+' 'strict-dynamic'/);
  expect(h["content-security-policy"]).toContain("frame-ancestors 'none'");
  expect(h["x-frame-options"]).toBe("DENY");
  expect(h["strict-transport-security"]).toContain("max-age=31536000");
  expect(h["x-content-type-options"]).toBe("nosniff");
  expect(h["x-powered-by"]).toBeUndefined();
});

test("a compliance officer can reveal the MSISDN, a viewer cannot", async ({ page }) => {
  await login(page, "ops-compliance01");
  await page.goto(`/console/transactions/${P2P}`);
  await expect(page.getByTestId("initiator")).toContainText("0171****678");
  await page.getByRole("link", { name: "Reveal MSISDN (audited)" }).click();
  await expect(page.getByTestId("initiator")).toContainText("01712345678");
  await expect(page.getByText("this view was audited")).toBeVisible();
});

test("signing out ends the session", async ({ page }) => {
  await login(page);
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/console\/login/);
  await page.goto("/console/transactions");
  await expect(page).toHaveURL(/\/console\/login\?callbackUrl=/);
});
