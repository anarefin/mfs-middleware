// Ticket 42: partner enable / disable and timeouts through maker-checker against the real mw-admin-api
// (ticket 38 rules; on approval the config is published at version + 1 to the adapters). The partner
// is the seeded `ocs`; every test puts it back as it found it, so a second run starts the same.
import { expect, test, type Browser, type Page } from "@playwright/test";

import { approve, asChecker, clearPending, filedRequestId, highlightedRow } from "./support/approvals";
import { persona } from "./support/personas";
import { operator } from "./support/session";

const PARTNER = "ocs";

async function facts(page: Page): Promise<{ enabled: boolean; version: number }> {
  const state = (await page.getByTestId("partner-state").textContent())?.trim();
  const v = (await page.getByTestId("partner-version").textContent())?.trim() ?? "";
  return { enabled: state === "enabled", version: v.startsWith("v") ? Number(v.slice(1)) : 0 };
}

/** The maker asks to flip `ocs`, the checker approves; it shows flipped at version + 1. */
async function flip(browser: Browser, maker: Page, enabled: boolean, version: number): Promise<void> {
  const action = enabled ? "DISABLE" : "ENABLE";
  await maker.goto(`/console/partners/${PARTNER}`);
  const form = maker.getByRole("form", { name: enabled ? "Disable partner" : "Enable partner" });
  await form.getByLabel("Reason").fill("partner incident INC-42 (e2e)");
  await form.getByRole("button", { name: enabled ? "Request disable" : "Request enable" }).click();
  const id = await filedRequestId(maker);
  const mine = highlightedRow(maker);
  await expect(mine).toContainText(action);
  await expect(mine).toContainText(`partner ${PARTNER}`);
  await expect(mine.getByTestId("diff")).toHaveText(`enabled=${enabled} -> enabled=${!enabled}`);
  await expect(mine.getByRole("button", { name: "Approve" })).toHaveCount(0);

  // not applied yet; the partner page shows the open request instead of the forms
  await maker.goto(`/console/partners/${PARTNER}`);
  await expect(maker.getByTestId("partner-state")).toHaveText(enabled ? "enabled" : "disabled");
  await expect(maker.getByTestId("pending-request")).toContainText(`${action} requested by ${persona("maker").username}`);
  await expect(maker.getByRole("region", { name: "Change partner" })).toHaveCount(0);

  await asChecker(browser, async (checker) => {
    const decided = await approve(checker, id);
    await expect(decided).toContainText(`published v${version + 1}`);
    await decided.getByRole("link", { name: `partner ${PARTNER}` }).click();
    await expect(checker.getByTestId("partner-state")).toHaveText(enabled ? "disabled" : "enabled");
    await expect(checker.getByTestId("partner-version")).toHaveText(`v${version + 1}`);
    await checker.getByRole("link", { name: "Back to partners" }).click();
    await expect(checker.getByTestId("partners").locator("tr", { hasText: PARTNER })).toContainText(enabled ? "disabled" : "enabled");
  });
}

/** The maker asks for a call timeout, the checker approves; the new value shows. */
async function setCallTimeout(browser: Browser, maker: Page, ms: number): Promise<void> {
  await maker.goto(`/console/partners/${PARTNER}`);
  const form = maker.getByRole("form", { name: "Partner timeouts" });
  await form.getByLabel("Call timeout (ms)").fill(String(ms));
  const read = Number(await form.getByLabel("Read (ms)").inputValue());
  if (read && read < ms) await form.getByLabel("Read (ms)").fill(String(ms));
  await form.getByLabel("Reason").fill("partner is slow at month end (e2e)");
  await form.getByRole("button", { name: "Request timeouts" }).click();
  const id = await filedRequestId(maker);
  await expect(highlightedRow(maker).getByTestId("diff")).toContainText(`timeoutMs=${ms}`);
  await asChecker(browser, async (checker) => {
    await approve(checker, id);
    await checker.goto(`/console/partners/${PARTNER}`);
    await expect(checker.getByTestId("partner-timeouts")).toContainText(`call ${ms} ms`);
    // a checker files nothing
    await expect(checker.getByRole("region", { name: "Change partner" })).toHaveCount(0);
  });
}

test.describe("partners", { tag: "@partners" }, () => {
  test("a maker disables a partner, the checker approves, it shows disabled at the next version (and back)", async ({ browser }) => {
    await clearPending(browser, `partner ${PARTNER}`);
    const maker = await operator(browser, "maker");
    await maker.getByRole("link", { name: "Partners" }).click();
    await maker.getByTestId("partners").getByRole("link", { name: PARTNER }).click();
    await expect(maker).toHaveURL(new RegExp(`/console/partners/${PARTNER}$`));
    const start = await facts(maker);

    await flip(browser, maker, start.enabled, start.version);
    // put it back for the stack's other suites and the next run
    await flip(browser, maker, !start.enabled, start.version + 1);
    await maker.context().close();
  });

  test("a maker changes a partner's timeouts, the checker approves, the new timeouts show", async ({ browser }) => {
    await clearPending(browser, `partner ${PARTNER}`);
    const maker = await operator(browser, "maker");
    await maker.goto(`/console/partners/${PARTNER}`);
    const form = maker.getByRole("form", { name: "Partner timeouts" });
    const before = Number(await form.getByLabel("Call timeout (ms)").inputValue());
    expect(before).toBeGreaterThan(0);

    // checked as the contract does before anything is sent
    await form.getByLabel("Call timeout (ms)").fill("0");
    await form.getByLabel("Reason").fill("partner is slow at month end");
    await form.getByRole("button", { name: "Request timeouts" }).click();
    await expect(form.locator(".error[role=alert]")).toContainText("MW-VAL-001");
    await expect(form.locator(".error[role=alert]")).toContainText("timeoutMs: a whole number 1 to 120000");

    await setCallTimeout(browser, maker, before === 21_000 ? 21_500 : 21_000);
    // back to where it was (the seed's baseline on a fresh stack)
    await setCallTimeout(browser, maker, before);
    await maker.context().close();
  });
});
