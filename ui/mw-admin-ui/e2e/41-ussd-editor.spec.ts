// Ticket 133a: the visual tree editor. A maker adds a child node under the start menu, fills its
// bn / en text side by side with the character counters, moves it, previews it on robi with the
// UCS-2 paging (0 next page, 00 back) and files it; a second persona approves. Go-live 2099 on every
// MNO, as in 40-ussd, so what mw-ussd serves (and the API suite reads) stays the seeded text.
// The server-side preview and the live walk over the test console come with 133b.
import { expect, test } from "@playwright/test";

import { approve, asChecker, clearPending, filedRequestId, highlightedRow } from "./support/approvals";
import { operator } from "./support/session";

const GO_LIVE = "2099-01-01T00:00";
const MNOS = ["gp", "robi", "bl", "teletalk"];
const LONG_BN = "এটি একটি দীর্ঘ পরীক্ষামূলক বার্তা যা একটি স্ক্রিনে আঁটে না তাই মেনুটি দুটি পাতায় ভাগ হয়ে দেখায়";

test.describe("ussd editor", { tag: "@ussd" }, () => {
  test("a maker adds, sizes, moves and previews a node, the checker approves it", async ({ browser }) => {
    await clearPending(browser, "ussd_menu main");
    const maker = await operator(browser, "maker");
    await maker.goto("/console/ussd?menuId=main");
    const tree = maker.getByTestId("menu-tree");
    const id = `e2e.info${Date.now() % 100000}`;

    // add a child END under the start menu: it becomes the start menu's last option
    await tree.getByRole("button").first().click();
    await maker.getByLabel("New child id").fill(id);
    await maker.getByLabel("New child type").selectOption("END");
    await maker.getByRole("button", { name: "Add child" }).click();
    await expect(tree.getByRole("button", { name: new RegExp(`^${id.replace(".", "\\.")}`) })).toBeVisible();

    // bn and en side by side, each with its counter; the long Bangla text runs over 70 and pages
    await maker.getByLabel("Text (bn)").fill(LONG_BN);
    await maker.getByLabel("Text (en)").fill("A test info screen");
    const counters = maker.getByTestId("char-count");
    await expect(counters.nth(0)).toHaveText(`${LONG_BN.length}/70 UCS-2`);
    await expect(counters.nth(0)).toHaveAttribute("data-over", "true");
    await expect(counters.nth(1)).toHaveText("18/182 GSM-7");
    await expect(maker.getByTestId("node-screen")).toContainText("pages");

    // preview on robi: dial, pick the new option, page through with 0 and back with 00
    const preview = maker.getByRole("region", { name: "Preview" });
    await preview.getByLabel("MNO").selectOption("robi");
    await expect(preview).toContainText("*269#");
    await preview.getByRole("button", { name: "Dial" }).click();
    await expect(preview.getByTestId("ussd-screen")).not.toBeEmpty();
    // the new option's key, read from the JSON view
    const json = JSON.parse(await maker.getByLabel("Menu JSON").inputValue()) as { start: string; nodes: Record<string, { options?: { key: string; next?: string }[] }> };
    const option = json.nodes[json.start]!.options!.find((o) => o.next === id)!;
    await preview.getByLabel("Reply", { exact: true }).fill(option.key);
    await preview.getByRole("button", { name: "Send" }).click();
    await expect(preview.getByTestId("ussd-page")).toHaveText("page 1/2");
    await expect(preview.getByTestId("ussd-screen")).toContainText("00. পেছনে");
    await preview.getByLabel("Reply", { exact: true }).fill("0");
    await preview.getByRole("button", { name: "Send" }).click();
    await expect(preview.getByTestId("ussd-page")).toHaveText("page 2/2");
    await expect(preview.getByTestId("ussd-ended")).toBeVisible();

    // delete it again: the start menu loses the option, the tree is as published
    await tree.getByRole("button", { name: new RegExp(`^${id.replace(".", "\\.")}`) }).click();
    await maker.getByRole("button", { name: "Delete node" }).click();
    await expect(tree.getByRole("button", { name: new RegExp(`^${id.replace(".", "\\.")}`) })).toHaveCount(0);
    const after = JSON.parse(await maker.getByLabel("Menu JSON").inputValue()) as typeof json;
    expect(after.nodes[after.start]!.options!.some((o) => o.next === id)).toBe(false);

    // add it once more and file it
    await tree.getByRole("button").first().click();
    await maker.getByLabel("New child id").fill(id);
    await maker.getByLabel("New child type").selectOption("END");
    await maker.getByRole("button", { name: "Add child" }).click();
    await maker.getByLabel("Text (bn)").fill("তথ্য");
    await maker.getByLabel("Text (en)").fill("Info");
    // its option label in the start menu, bn and en
    await tree.getByRole("button").first().click();
    const startOptions = maker.locator(`[aria-label="Option ${option.key}"]`);
    await startOptions.getByLabel(`Option ${option.key} label (bn)`).fill("তথ্য");
    await startOptions.getByLabel(`Option ${option.key} label (en)`).fill("Info");
    await expect(maker.getByTestId("menu-problems")).toContainText("No problems found");

    const propose = maker.getByRole("form", { name: "Menu proposal" });
    for (const mno of MNOS) await propose.getByLabel(`Go-live ${mno}`).fill(GO_LIVE);
    await propose.getByLabel("Reason").fill("info node from the visual editor (e2e, goes live in 2099)");
    await propose.getByRole("button", { name: "Request publish" }).click();
    const requestId = await filedRequestId(maker);
    await expect(highlightedRow(maker)).toContainText("ussd_menu main");

    await asChecker(browser, async (checker) => {
      const decided = await approve(checker, requestId);
      await expect(decided).toContainText(/published v\d+/);
      await decided.getByRole("link", { name: "ussd_menu main" }).click();
      await expect(checker.getByTestId("menu-tree")).toContainText(id);
    });
    await maker.context().close();
  });
});
