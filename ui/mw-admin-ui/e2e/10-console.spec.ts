// Ticket 40: search, transaction detail, what the browser may see. The transaction is real: a seeded
// customer sends money over USSD (support/ussd.ts) and the console reads it from mw-admin-api.
import { expect, test } from "@playwright/test";

import { CUSTOMER_A, CUSTOMER_B } from "./support/env";
import { persona } from "./support/personas";
import { signIn } from "./support/session";
import { need, readState, saveState } from "./support/state";
import { p2pOverUssd } from "./support/ussd";

// a signed access / id / refresh token (JWS); the session cookie is a JWE and never matches
const JWS = /eyJ[\w-]{8,}\.eyJ[\w-]{8,}\.[\w-]{16,}/;

test.describe("console", { tag: "@console" }, () => {
  test("an operator finds a transaction by txnId and by MSISDN and reads its timeline", async ({ page }) => {
    // the viewer's cached login happens here, after 00-smoke's own: it may wait up to 30 s for an unused TOTP step
    test.setTimeout(150_000);
    const txnId = await p2pOverUssd(CUSTOMER_A, CUSTOMER_B);
    saveState({ TXN_P2P: txnId });

    const cspViolations: string[] = [];
    page.on("console", (m) => {
      if (m.text().includes("Content Security Policy") && page.url().includes("/console/")) cspViolations.push(m.text());
    });
    await signIn(page, "viewer");

    // by txnId (the read model trails the saga a little: search again until it shows COMPLETED)
    const rows = page.getByTestId("results").locator("tbody tr");
    await expect(async () => {
      await page.goto("/console/transactions");
      await page.getByLabel("Transaction ID").fill(txnId);
      await page.getByRole("button", { name: "Search" }).click();
      await expect(rows).toHaveCount(1, { timeout: 3_000 });
      await expect(rows.first()).toContainText("COMPLETED", { timeout: 3_000 });
    }).toPass({ timeout: 60_000 });
    await expect(rows.first()).toContainText(txnId);
    await expect(rows.first()).toContainText("BDT 10.00");

    // by MSISDN, in the +880 form with spaces: newest first, so this run's P2P is on the first page
    await page.getByLabel("Transaction ID").fill("");
    await page.getByLabel("MSISDN").fill(`+880 ${CUSTOMER_A.slice(1, 5)}-${CUSTOMER_A.slice(5)}`);
    await page.getByRole("button", { name: "Search" }).click();
    await expect(page.getByTestId("results")).toContainText(txnId);
    // masked as the admin API returns it, never the number searched for
    await expect(page.getByTestId("results")).toContainText(`${CUSTOMER_A.slice(0, 4)}****${CUSTOMER_A.slice(-3)}`);
    await expect(page.getByTestId("results")).not.toContainText(CUSTOMER_A);

    // detail with the saga timeline
    await page.getByRole("link", { name: txnId }).click();
    await expect(page).toHaveURL(new RegExp(`/console/transactions/${txnId}$`));
    await expect(page.getByTestId("status")).toContainText("COMPLETED");
    await expect(page.getByText(/^P2P \/ USSD/)).toBeVisible();
    await expect(page.getByTestId("timeline").locator("li").last()).toContainText("-> COMPLETED");
    // a viewer is not offered the reveal, nor any action
    await expect(page.getByText("Reveal MSISDN")).toHaveCount(0);
    await expect(page.getByRole("region", { name: "Actions" })).toHaveCount(0);

    // an unknown transaction shows the admin API's envelope
    await page.goto("/console/transactions/00000000-0000-4000-8000-000000000000");
    await expect(page.locator(".error[role=alert]")).toContainText("MW-VAL-003");

    // the nonce CSP let every script of the console run
    expect(cspViolations).toEqual([]);
  });

  test("the browser never receives the access token", async ({ page, context }) => {
    const { TXN_P2P } = readState();
    need(!TXN_P2P, "no TXN_P2P from the search test");
    await signIn(page, "viewer");

    const seen: string[] = [];
    page.on("request", (r) => {
      if (r.url().includes("/console")) seen.push(r.url(), JSON.stringify(r.headers()), r.postData() ?? "");
    });
    page.on("response", async (r) => {
      if (!r.url().includes("/console")) return;
      seen.push(JSON.stringify(await r.allHeaders()));
      try {
        seen.push(await r.text());
      } catch {
        // redirects have no body
      }
    });

    await page.getByLabel("MSISDN").fill(CUSTOMER_A);
    await page.getByRole("button", { name: "Search" }).click();
    await page.getByRole("link", { name: TXN_P2P! }).click();
    await expect(page.getByTestId("timeline")).toBeVisible();

    // what scripts in the page can read
    seen.push(await page.evaluate(() => document.cookie));
    seen.push(await page.evaluate(() => JSON.stringify({ ...localStorage })));
    seen.push(await page.evaluate(() => JSON.stringify({ ...sessionStorage })));
    seen.push(await page.content());
    // the session endpoint the browser can call: who and which roles, never a token
    const sessionBody = await (await page.request.get("/console/api/auth/session")).text();
    seen.push(sessionBody);
    const session = JSON.parse(sessionBody) as { user: { name: string }; roles: string[] } & Record<string, unknown>;
    expect(session.user.name).toBe(persona("viewer").username);
    expect(session.roles).toContain("viewer");
    expect(Object.keys(session).sort()).toEqual(expect.arrayContaining(["expires", "roles", "user"]));
    for (const k of ["accessToken", "refreshToken", "idToken", "access_token"]) expect(session[k]).toBeUndefined();

    const all = seen.join("\n");
    expect(JWS.exec(all)?.[0], "a signed token reached the browser").toBeUndefined();

    // the session cookie is HTTP-only, SameSite=Lax and encrypted (a JWE, not the token)
    const sessionCookies = (await context.cookies()).filter((c) => c.name.includes("authjs.session-token"));
    expect(sessionCookies.length).toBeGreaterThan(0);
    for (const c of sessionCookies) {
      expect(c.httpOnly).toBe(true);
      expect(c.secure).toBe(true);
      expect(c.sameSite).toBe("Lax");
      expect(JWS.test(c.value)).toBe(false);
    }
  });

  test("only a compliance officer or auditor is offered the reveal; a viewer's ?reveal=true stays masked", async ({ page }) => {
    // The seed has no compliance persona (maker, checker, viewer); the reveal itself is mw-admin-api's
    // (audited) and covered by the API suite. The console must not pass reveal=true for a viewer.
    const { TXN_P2P } = readState();
    need(!TXN_P2P, "no TXN_P2P from the search test");
    await signIn(page, "viewer");
    await page.goto(`/console/transactions/${TXN_P2P}?reveal=true`);
    await expect(page.getByTestId("initiator")).toContainText(`${CUSTOMER_A.slice(0, 4)}****${CUSTOMER_A.slice(-3)}`);
    await expect(page.getByText("this view was audited")).toHaveCount(0);
    await expect(page.getByRole("link", { name: "Reveal MSISDN (audited)" })).toHaveCount(0);
    expect(await page.content()).not.toContain(CUSTOMER_A);
  });
});
