// Ticket 42: USSD menu tree editor with a preview walk; the new version goes through maker-checker
// against the real mw-admin-api (ticket 39 rules). The seeded menu `main` (mw-seeder) is the base.
// Every MNO's go-live is set far in the future, so the published version never reaches a subscriber:
// what mw-ussd serves (and the API suite reads) stays the seeded text.
import { expect, test, type Page } from "@playwright/test";

import { approve, asChecker, clearPending, filedRequestId, highlightedRow } from "./support/approvals";
import { persona } from "./support/personas";
import { operator } from "./support/session";

const DONE_BN = "টাকা সফলভাবে পাঠানো হয়েছে। TxnID {txnId}";
const GO_LIVE = "2099-01-01T00:00";
const MNOS = ["gp", "robi", "bl", "teletalk"];

const versionRow = (page: Page, v: number) =>
  page.getByTestId("menu-versions").locator("tbody tr").filter({ has: page.getByRole("cell", { name: `v${v}`, exact: true }) });

async function walkSendMoney(page: Page, outcome = "completed") {
  const preview = page.getByRole("region", { name: "Preview" });
  await preview.getByLabel("Action outcome").selectOption(outcome);
  await preview.getByRole("button", { name: "Dial" }).click();
  for (const reply of ["1", "01812345321", "500", "1", "1234"]) {
    await preview.getByLabel("Reply", { exact: true }).fill(reply);
    await preview.getByRole("button", { name: "Send" }).click();
  }
  return preview;
}

test.describe("ussd", { tag: "@ussd" }, () => {
  test("a maker edits a Bangla text, walks it in the preview, the checker approves, the new version is published", async ({ browser }) => {
    await clearPending(browser, "ussd_menu main");
    const maker = await operator(browser, "maker");
    await maker.getByRole("link", { name: "USSD menus" }).click();
    const tree = maker.getByRole("region", { name: "Menu tree" });
    await expect(tree).toContainText(/edited from v\d+ \(PUBLISHED\)/);

    // the preview walks the published tree first (Bangla, mw-ussd's default)
    const preview = maker.getByRole("region", { name: "Preview" });
    const own = await preview.getByLabel("Dialling MSISDN (sample)").inputValue();
    await preview.getByRole("button", { name: "Dial" }).click();
    await expect(preview.getByTestId("ussd-screen")).toContainText("1 সেন্ড মানি");
    await preview.getByLabel("Reply", { exact: true }).fill("1");
    await preview.getByRole("button", { name: "Send" }).click();
    await preview.getByLabel("Reply", { exact: true }).fill(own); // own number: notSelf
    await preview.getByRole("button", { name: "Send" }).click();
    await expect(preview.getByTestId("ussd-screen")).toHaveText("সঠিক মোবাইল নম্বর দিন।\nপ্রাপকের Bits নম্বর দিন");

    // edit the success screen in Bangla and walk to it
    await maker.getByTestId("menu-tree").getByRole("button", { name: /^p2p\.done/ }).click();
    await maker.getByLabel("Text (bn)").fill(DONE_BN);
    await walkSendMoney(maker);
    await expect(preview.getByTestId("ussd-screen")).toHaveText("টাকা সফলভাবে পাঠানো হয়েছে। TxnID PREVIEW-TXN");
    await expect(preview.getByTestId("ussd-ended")).toBeVisible();
    // the PIN never shows, not even in the trail
    await expect(preview.locator("ol.trail li")).toHaveText(["(dial)", "1", "01812345321", "500", "1", "****"]);
    await expect(maker.getByTestId("menu-problems")).toContainText("No problems found");

    // a text without English is flagged before it is filed
    await maker.getByLabel("Text (en)").fill("");
    await expect(maker.getByTestId("menu-problems")).toContainText("nodes.p2p.done.text: needs bn and en");
    await maker.getByLabel("Text (en)").fill("Money sent. TxnID {txnId}");

    const propose = maker.getByRole("form", { name: "Menu proposal" });
    for (const mno of MNOS) await propose.getByLabel(`Go-live ${mno}`).fill(GO_LIVE);
    await propose.getByLabel("Reason").fill("clearer success text (e2e, goes live in 2099)");
    await propose.getByRole("button", { name: "Request publish" }).click();
    const id = await filedRequestId(maker);
    const mine = highlightedRow(maker);
    await expect(mine).toContainText("PUBLISH");
    await expect(mine).toContainText("ussd_menu main");
    await expect(mine.getByTestId("diff")).toHaveText(/^v\d+ -> v\d+$/);
    const version = Number(/-> v(\d+)$/.exec((await mine.getByTestId("diff").textContent())!.trim())![1]);
    await expect(mine.getByRole("button", { name: "Approve" })).toHaveCount(0);

    // while it waits: shown as PENDING, a second proposal is not offered
    await maker.goto("/console/ussd?menuId=main");
    await expect(versionRow(maker, version)).toContainText("PENDING");
    await expect(maker.getByTestId("pending-request")).toContainText(`PUBLISH requested by ${persona("maker").username}`);
    await expect(maker.getByRole("form", { name: "Menu proposal" })).toHaveCount(0);

    await asChecker(browser, async (checker) => {
      const decided = await approve(checker, id);
      await expect(decided).toContainText(`published v${version}`);

      // effect: the version is published with its schedule, and the editor and preview start from it
      await decided.getByRole("link", { name: "ussd_menu main" }).click();
      const row = versionRow(checker, version);
      await expect(row).toContainText("PUBLISHED");
      for (const mno of MNOS) await expect(row).toContainText(`${mno} 2099-01-01 00:00:00 UTC`);
      await expect(checker.getByRole("region", { name: "Menu tree" })).toContainText(`edited from v${version} (PUBLISHED)`);
      await walkSendMoney(checker);
      await expect(checker.getByTestId("ussd-screen")).toHaveText("টাকা সফলভাবে পাঠানো হয়েছে। TxnID PREVIEW-TXN");
      // a checker proposes nothing
      await expect(checker.getByRole("form", { name: "Menu proposal" })).toHaveCount(0);
    });
    await maker.context().close();
  });

  test("a broken tree pasted as JSON is refused with the API's details", async ({ browser }) => {
    await clearPending(browser, "ussd_menu main");
    const maker = await operator(browser, "maker");
    await maker.goto("/console/ussd");
    await maker.getByText("JSON", { exact: true }).click();
    const json = maker.getByLabel("Menu JSON");
    const tree = JSON.parse(await json.inputValue());
    tree.nodes["p2p.amount"].next = "nowhere";
    await json.fill(JSON.stringify(tree));
    await maker.getByRole("button", { name: "Apply JSON" }).click();
    await expect(maker.getByTestId("menu-problems")).toContainText("nodes.p2p.amount.next: no node nowhere");

    const propose = maker.getByRole("form", { name: "Menu proposal" });
    await propose.getByLabel("Reason").fill("try it");
    await propose.getByRole("button", { name: "Request publish" }).click();
    await expect(propose.locator(".error[role=alert]")).toContainText("MW-VAL-040");
    await expect(propose.locator(".error[role=alert]")).toContainText("menu.nodes.p2p.amount.next: no node nowhere");
    await maker.context().close();
  });
});
