import { expect, type Browser, type Locator, type Page } from "@playwright/test";
import { persona } from "./personas";
import { CONTEXT_OPTIONS, signIn } from "./session";

/**
 * The approvals inbox on the live stack keeps every earlier run's requests, so rows are found by the
 * request id (the maker lands on `?created=<id>`, the decide forms are labelled `Approve <id>`), never
 * by resource text alone.
 */

/** The id of the request the maker just filed (the inbox URL after filing). */
export async function filedRequestId(maker: Page): Promise<string> {
  await expect(maker).toHaveURL(/\/console\/approvals\?created=/);
  const id = new URL(maker.url()).searchParams.get("created");
  expect(id).toMatch(/^[0-9a-f-]{36}$/);
  return id!;
}

/** The inbox row of request `id` (shown highlighted after filing or deciding it). */
export const highlightedRow = (page: Page): Locator => page.getByTestId("change-requests").locator("tbody tr.highlight");

/** The pending row of request `id` in the PENDING inbox. */
export function pendingRow(page: Page, id: string): Locator {
  return page.getByTestId("change-requests").locator("tbody tr").filter({ has: page.getByRole("form", { name: `Approve ${id}` }) });
}

/** The checker in a browser context of their own (sessions are per context), closed afterwards. */
export async function asChecker<T>(browser: Browser, fn: (page: Page) => Promise<T>): Promise<T> {
  const ctx = await browser.newContext(CONTEXT_OPTIONS);
  try {
    const page = await ctx.newPage();
    await signIn(page, "checker");
    return await fn(page);
  } finally {
    await ctx.close();
  }
}

/** Approves request `id` from the inbox; lands on the decided row. */
export async function approve(checker: Page, id: string): Promise<Locator> {
  await checker.goto("/console/approvals");
  await pendingRow(checker, id).getByRole("button", { name: "Approve" }).click();
  await expect(checker).toHaveURL(new RegExp(`decided=${id}`));
  const row = highlightedRow(checker);
  await expect(row).toContainText("APPROVED");
  return row;
}

/** Rejects request `id` with `reason`; lands on the decided row. */
export async function reject(checker: Page, id: string, reason: string): Promise<Locator> {
  await checker.goto("/console/approvals");
  const row = pendingRow(checker, id);
  await row.getByLabel("Rejection reason").fill(reason);
  await row.getByRole("button", { name: "Reject" }).click();
  await expect(checker).toHaveURL(new RegExp(`decided=${id}`));
  const decided = highlightedRow(checker);
  await expect(decided).toContainText("REJECTED");
  return decided;
}

/**
 * Rejects what an earlier, interrupted run left PENDING for a resource (one open request per
 * resource, so a leftover would block this run's request). Only the seed maker's requests.
 */
export async function clearPending(browser: Browser, resourceText: string): Promise<void> {
  const maker = persona("maker").username;
  await asChecker(browser, async (page) => {
    for (let i = 0; i < 10; i++) {
      await page.goto("/console/approvals");
      const rows = page.locator("[data-testid=change-requests] tbody tr").filter({ hasText: resourceText }).filter({ hasText: maker });
      if ((await rows.count()) === 0) return;
      const row = rows.first();
      await row.getByLabel("Rejection reason").fill("left over by an interrupted e2e run");
      await row.getByRole("button", { name: "Reject" }).click();
      await expect(page).toHaveURL(/decided=/);
    }
  });
}
