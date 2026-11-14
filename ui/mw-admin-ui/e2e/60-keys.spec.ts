// Ticket 75 (spec 18.8, D23, OWN-08): the keys page and the dual-control key ceremony against the
// real mw-admin-api and mw-crypto (SoftHSM): two security-admins (seed personas secadmin1 and
// secadmin2, each in their own browser context and session) enter one component each, the HSM
// combines them and the wizard shows the KCV the test computed from the two components; the second
// security-admin approves and the key's version 1 is ACTIVE; then a rotation filed by one is
// approved by the other. Every run uses its own keyId, so the run is idempotent. Key actions need a
// sign-in of the last 5 minutes: the secadmin personas sign in for the first time in this spec.
import { createECDH, createHash, createHmac, randomBytes } from "node:crypto";
import { expect, test, type Browser, type Page } from "@playwright/test";

import { filedRequestId, highlightedRow, pendingRow } from "./support/approvals";
import { persona, type PersonaName } from "./support/personas";
import { CONTEXT_OPTIONS, signIn } from "./support/session";

const N = BigInt("0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551");

/** A component as a custodian's paper share: a P-256 scalar 0 < d < n, 64 hex digits. */
function component(): Buffer {
  for (;;) {
    const b = randomBytes(32);
    const d = BigInt(`0x${b.toString("hex")}`);
    if (d > 0n && d < N) return b;
  }
}

/** mw-crypto's component check value: SHA-256 of the compressed d·G, first 3 bytes. */
function checkValue(c: Buffer): string {
  const ecdh = createECDH("prime256v1");
  ecdh.setPrivateKey(c);
  return createHash("sha256").update(ecdh.getPublicKey(null, "compressed")).digest("hex").slice(0, 6).toUpperCase();
}

/** The KCV of the key the HSM derives: HMAC-SHA256 over 16 zero bytes under x(d1·d2·G), first 3 bytes. */
function expectedKcv(c1: Buffer, c2: Buffer): string {
  const a = createECDH("prime256v1");
  a.setPrivateKey(c1);
  const b = createECDH("prime256v1");
  b.setPrivateKey(c2);
  const secret = a.computeSecret(b.getPublicKey());
  return createHmac("sha256", secret).update(Buffer.alloc(16)).digest("hex").slice(0, 6).toUpperCase();
}

async function as<T>(browser: Browser, name: PersonaName, fn: (page: Page) => Promise<T>): Promise<T> {
  const ctx = await browser.newContext(CONTEXT_OPTIONS);
  try {
    const page = await ctx.newPage();
    await signIn(page, name);
    return await fn(page);
  } finally {
    await ctx.close();
  }
}

async function enter(page: Page, ceremonyUrl: string, c: Buffer) {
  await page.goto(ceremonyUrl);
  const form = page.getByRole("form", { name: "Component" });
  await form.getByLabel("Component").fill(c.toString("hex"));
  await form.getByLabel("Check value").fill(checkValue(c));
  await form.getByRole("button", { name: "Enter component" }).click();
  await expect(page).toHaveURL(/entered=/);
}

test.describe("keys", { tag: "@keys" }, () => {
  const keyId = `hmac-e2e-${Date.now().toString(36)}`;
  const purpose = "HMAC_E2E";

  test("two custodians run a key ceremony and a second security-admin approves it", async ({ browser }) => {
    test.setTimeout(240_000);
    const sec1 = persona("secadmin1").username;
    const sec2 = persona("secadmin2").username;
    const c1 = component();
    const c2 = component();
    const kcv = expectedKcv(c1, c2);

    // custodian 1 starts the ceremony and enters component 1
    const ceremonyUrl = await as(browser, "secadmin1", async (page) => {
      await page.goto("/console/keys");
      await expect(page.getByTestId("keys")).toBeVisible();
      const start = page.getByRole("form", { name: "Start key ceremony" });
      await start.getByLabel("Key id").fill(keyId);
      await start.getByLabel("Purpose").fill(purpose);
      await start.getByLabel("Reason").fill("e2e key ceremony");
      await start.getByRole("button", { name: "Start ceremony" }).click();
      await expect(page).toHaveURL(/\/console\/keys\/ceremony\/[0-9a-f-]{36}/);
      const url = new URL(page.url()).pathname.replace(/^\/console/, "");
      await expect(page.getByTestId("ceremony-steps").locator("[aria-current=step]")).toHaveText(/Custodian 1/);
      await enter(page, `/console${url}`, c1);
      await expect(page.getByTestId("ceremony-shares")).toContainText(`Share 1: ${sec1}`);
      // the same custodian is not offered the second share
      await expect(page.getByRole("form", { name: "Component" })).toHaveCount(0);
      await expect(page.getByText(c1.toString("hex"))).toHaveCount(0);
      return `/console${url}`;
    });
    const id = ceremonyUrl.split("/").pop()!;

    // custodian 2, in a session of their own, enters component 2; the HSM answers the KCV only
    await as(browser, "secadmin2", async (page) => {
      await enter(page, ceremonyUrl, c2);
      await expect(page.getByTestId("ceremony-shares")).toContainText(`Share 2: ${sec2}`);
      await expect(page.getByTestId("kcv")).toHaveText(kcv);
      const compare = page.getByRole("form", { name: "Compare KCV" });
      await compare.getByLabel("Expected KCV").fill(kcv.toLowerCase());
      await compare.getByRole("button", { name: "Compare" }).click();
      await expect(page.getByText("The KCV matches.")).toBeVisible();

      // the checker (not the maker, secadmin1) approves in the inbox
      await page.goto("/console/approvals");
      const row = pendingRow(page, id);
      await expect(row).toContainText(`KCV ${kcv}`);
      await expect(row).toContainText(sec1);
      await row.getByRole("button", { name: "Approve" }).click();
      await expect(page).toHaveURL(new RegExp(`decided=${id}`));
      await expect(highlightedRow(page)).toContainText("APPROVED");

      await page.goto("/console/keys");
      const key = page.getByTestId("keys").locator(`tr[data-key="${keyId}"]`);
      await expect(key.locator(`[data-kid="${keyId}.v1"]`)).toContainText("ACTIVE");
    });
  });

  test("a rotation filed by one security-admin is approved by the other, never by the maker", async ({ browser }) => {
    test.setTimeout(180_000);
    const id = await as(browser, "secadmin1", async (page) => {
      await page.goto("/console/keys");
      const key = page.getByTestId("keys").locator(`tr[data-key="${keyId}"]`);
      test.skip((await key.count()) === 0, "the ceremony test did not make the key");
      const rotate = key.getByRole("form", { name: `Rotate ${keyId}` });
      await rotate.getByLabel("Reason").fill("e2e rotation");
      await rotate.getByRole("button", { name: "Request rotation" }).click();
      const filed = await filedRequestId(page);
      const mine = highlightedRow(page);
      await expect(mine).toContainText("KEY_ROTATE");
      await expect(mine.getByTestId("diff")).toHaveText(`${keyId}.v1 ACTIVE -> ${keyId}.v2 ACTIVE`);
      await expect(mine.getByRole("button", { name: "Approve" })).toHaveCount(0);
      return filed;
    });

    await as(browser, "secadmin2", async (page) => {
      await page.goto("/console/approvals");
      await pendingRow(page, id).getByRole("button", { name: "Approve" }).click();
      await expect(page).toHaveURL(new RegExp(`decided=${id}`));
      await expect(highlightedRow(page)).toContainText(`applied ${keyId}.v2`);
      await page.goto("/console/keys");
      const key = page.getByTestId("keys").locator(`tr[data-key="${keyId}"]`);
      await expect(key.locator(`[data-kid="${keyId}.v2"]`)).toContainText("ACTIVE");
      await expect(key.locator(`[data-kid="${keyId}.v1"]`)).toContainText("VERIFY_ONLY");
    });
  });
});
