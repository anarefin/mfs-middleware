import { expect, type Browser, type BrowserContext, type Page } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import { BASE_URL } from "./env";
import { persona, type Persona, type PersonaName } from "./personas";
import { readState, saveState, STATE_DIR } from "./state";
import { totp, totpStep } from "./totp";

/**
 * Real Keycloak logins (realm ops, client mw-admin-ui: code + PKCE, password then TOTP). The console
 * keeps its session in an HTTP-only cookie, so one login per persona per run is cached as Playwright
 * storageState (e2e/.state/sessions/<persona>.json) and handed to every later context. Keycloak
 * refuses a TOTP code a user already used, so a login waits for a step that user has not used yet
 * (also across worker restarts: the steps are in run.json). An APISIX 429 or a refused code is
 * retried inside toPass.
 */
export const CONTEXT_OPTIONS = { baseURL: BASE_URL, ignoreHTTPSErrors: true, viewport: { width: 1440, height: 1000 } };

const sessionFile = (name: PersonaName) => path.join(STATE_DIR, "sessions", `${name}.json`);

async function freshCode(p: Persona): Promise<string> {
  const used = readState().totpSteps?.[p.username] ?? -1;
  let now = Date.now();
  // a step this user has not used, with at least 3 s of it left for the form round trip
  let step = totpStep(now);
  if (step <= used || (step + 1) * 30_000 - now < 3_000) {
    step = Math.max(used, step) + 1;
    await new Promise((r) => setTimeout(r, step * 30_000 - now + 250));
    now = Date.now();
  }
  saveState({ totpSteps: { ...readState().totpSteps, [p.username]: step } });
  return totp(p.totpSecret, step);
}

/**
 * Signs in through the console's login page and Keycloak; lands on the search. Returns the
 * authorization request URL the console sent the browser to (PKCE, state, nonce).
 */
export async function keycloakLogin(page: Page, name: PersonaName): Promise<string> {
  const p = persona(name);
  let authorizeUrl = "";
  await expect(async () => {
    let limited = 0;
    const on429 = (r: { status(): number }) => { if (r.status() === 429) limited++; };
    page.on("response", on429);
    try {
      // settles either way, so a failed attempt leaves no dangling rejection behind
      const authorize = page
        .waitForRequest(/\/realms\/ops\/protocol\/openid-connect\/auth\?/, { timeout: 20_000 })
        .then((r) => r.url(), () => "");
      await page.goto("/console/transactions");
      await expect(page).toHaveURL(/\/console\/login/);
      await page.getByRole("button", { name: "Sign in with Keycloak" }).click();
      authorizeUrl = await authorize;
      const username = page.locator("#username");
      const otp = page.locator("#otp");
      await expect(username.or(otp).first()).toBeVisible();
      if (await username.isVisible()) {
        await username.fill(p.username);
        await page.locator("#password").fill(p.password);
        await page.locator("#kc-login").click();
      }
      await expect(otp).toBeVisible();
      await otp.fill(await freshCode(p));
      await page.locator("#kc-login").click();
      await expect(page).toHaveURL(/\/console\/transactions$/, { timeout: 20_000 });
      await expect(page.getByTestId("operator")).toContainText(p.username);
    } finally {
      page.off("response", on429);
    }
    if (limited) throw new Error(`rate limited (${limited}x 429), retrying`);
  }).toPass({ timeout: 80_000, intervals: [2_000, 5_000, 15_000] });
  return authorizeUrl;
}

async function signedInAs(page: Page, name: PersonaName): Promise<boolean> {
  await page.goto("/console/transactions");
  if (!/\/console\/transactions$/.test(page.url())) return false;
  return (await page.getByTestId("operator").textContent())?.includes(persona(name).username) ?? false;
}

const prefetchFree = new WeakSet<Page>();

/**
 * Drops Next's link prefetches (header `Next-Router-Prefetch`) in the suite's pages: every console
 * page prefetches its six nav links, which pushed a whole run past APISIX's `admin-ui` quota (600
 * requests a minute per address) and turned pages into 429s. A click then loads the page itself, as
 * an operator whose prefetch failed sees it. Through Chromium's Fetch domain, not `page.route`:
 * Playwright routing turns the HTTP cache off, and the static chunks fetched again on every page
 * load are more requests than the prefetches.
 */
async function skipPrefetches(page: Page): Promise<void> {
  if (prefetchFree.has(page)) return;
  prefetchFree.add(page);
  const cdp = await page.context().newCDPSession(page);
  cdp.on("Fetch.requestPaused", (e: { requestId: string; request: { headers: Record<string, string> } }) => {
    const prefetch = Object.keys(e.request.headers).some((h) => h.toLowerCase() === "next-router-prefetch");
    const done = prefetch
      ? cdp.send("Fetch.failRequest", { requestId: e.requestId, errorReason: "Aborted" })
      : cdp.send("Fetch.continueRequest", { requestId: e.requestId });
    done.catch(() => undefined); // the page may be gone already
  });
  await cdp.send("Fetch.enable", { patterns: [{ urlPattern: "*/console/*", requestStage: "Request" }] });
}

/**
 * Puts `name` signed in into the page's context: the run's cached session when it still works, else
 * a real login (cached for the rest of the run). Lands on the search.
 */
export async function signIn(page: Page, name: PersonaName): Promise<void> {
  await skipPrefetches(page);
  const file = sessionFile(name);
  if (fs.existsSync(file)) {
    const cached = JSON.parse(fs.readFileSync(file, "utf8")) as { cookies: Parameters<BrowserContext["addCookies"]>[0] };
    await page.context().addCookies(cached.cookies);
    if (await signedInAs(page, name)) return;
    await page.context().clearCookies();
  }
  await keycloakLogin(page, name);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  await page.context().storageState({ path: file });
}

/** A signed-in operator in a browser context of their own (maker and checker side by side). */
export async function operator(browser: Browser, name: PersonaName): Promise<Page> {
  const ctx = await browser.newContext(CONTEXT_OPTIONS);
  const page = await ctx.newPage();
  await signIn(page, name);
  return page;
}
