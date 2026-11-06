// The console is up behind APISIX and a real Keycloak login works (ticket 40), before anything else.
import { expect, test } from "@playwright/test";

import { persona } from "./support/personas";
import { CONTEXT_OPTIONS, keycloakLogin } from "./support/session";

test.describe("smoke", { tag: "@smoke" }, () => {
  test("pages carry CSP, HSTS and X-Frame-Options DENY", async ({ page }) => {
    const res = await page.goto("/console/login");
    expect(res?.status()).toBe(200);
    const h = res!.headers();
    expect(h["content-security-policy"]).toMatch(/script-src 'self' 'nonce-[^']+' 'strict-dynamic'/);
    expect(h["content-security-policy"]).toContain("frame-ancestors 'none'");
    expect(h["x-frame-options"]).toBe("DENY");
    expect(h["strict-transport-security"]).toContain("max-age=31536000");
    expect(h["x-content-type-options"]).toBe("nosniff");
    expect(h["x-powered-by"]).toBeUndefined();
  });

  test("an operator signs in with password and one-time code (code + PKCE), and signing out ends the session", async ({ browser }) => {
    // its own login, not the run's cached one: signing out ends this Keycloak session only
    const ctx = await browser.newContext(CONTEXT_OPTIONS);
    const page = await ctx.newPage();
    const authorize = new URL(await keycloakLogin(page, "viewer"));
    expect(authorize.searchParams.get("client_id")).toBe("mw-admin-ui");
    expect(authorize.searchParams.get("response_type")).toBe("code");
    expect(authorize.searchParams.get("code_challenge_method")).toBe("S256");
    expect(authorize.searchParams.get("code_challenge")).toBeTruthy();
    expect(authorize.searchParams.get("state")).toBeTruthy();
    expect(authorize.searchParams.get("nonce")).toBeTruthy();
    await expect(page.getByTestId("operator")).toContainText(persona("viewer").username);
    await expect(page.getByTestId("operator")).toContainText("viewer");
    await expect(page.getByTestId("operator")).not.toContainText("ops-maker");

    await page.getByRole("button", { name: "Sign out" }).click();
    await expect(page).toHaveURL(/\/console\/login/);
    await page.goto("/console/transactions");
    await expect(page).toHaveURL(/\/console\/login\?callbackUrl=/);
    await ctx.close();
  });
});
