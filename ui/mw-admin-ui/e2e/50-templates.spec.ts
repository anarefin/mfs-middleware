// Ticket 42: template editor with Bangla input and a live preview on sample params (masked as
// mw-notify masks them); a new version goes through maker-checker against the real mw-admin-api
// (ticket 39 rules). The template is the seeded TXN_P2P_RECEIVED/SMS/bn (mw-seeder). Proposals are
// valid from 2099, so mw-notify keeps sending the seeded text the API suite reads.
import { expect, test, type Page } from "@playwright/test";

import { approve, asChecker, clearPending, filedRequestId, highlightedRow, reject } from "./support/approvals";
import { operator } from "./support/session";

const CODE = "TXN_P2P_RECEIVED";
const KEY = `${CODE}/SMS/bn`;
const PAGE = `/console/templates?code=${CODE}&channel=SMS&locale=bn`;
const NEW_BODY = "{{counterparty}} নম্বর থেকে ৳{{amount}} পেয়েছেন। TrxID {{txnId}}";
const VALID_FROM = "2099-01-01T00:00";

const versionRow = (page: Page, v: number) =>
  page.getByTestId("template-versions").locator("tbody tr").filter({ has: page.getByRole("cell", { name: `v${v}`, exact: true }) });

/** The version a proposal row leads to (`vN -> vM`). */
async function proposedVersion(row: ReturnType<typeof highlightedRow>): Promise<number> {
  await expect(row.getByTestId("diff")).toHaveText(/^v\d+ -> v\d+$/);
  return Number(/-> v(\d+)$/.exec((await row.getByTestId("diff").textContent())!.trim())![1]);
}

async function propose(maker: Page, body: string, reason: string): Promise<{ id: string; version: number }> {
  await maker.goto(PAGE);
  await maker.getByLabel("Body (bn)").fill(body);
  const form = maker.getByRole("form", { name: "Template proposal" });
  await form.getByLabel("Valid from (UTC, empty = at approval)").fill(VALID_FROM);
  await form.getByLabel("Reason").fill(reason);
  await form.getByRole("button", { name: "Request publish" }).click();
  const id = await filedRequestId(maker);
  return { id, version: await proposedVersion(highlightedRow(maker)) };
}

test.describe("templates", { tag: "@templates" }, () => {
  test("the preview renders Bangla text with sample params, masked", async ({ browser }) => {
    const viewer = await operator(browser, "viewer");
    await viewer.goto(PAGE);
    // the newest published text is loaded into the editor
    const live = (await viewer.getByTestId("template-live").textContent()) ?? "";
    expect(live).toContain("{{amount}}");
    await expect(viewer.getByLabel("Body (bn)")).toHaveValue(live);

    const preview = viewer.getByTestId("template-preview");
    await viewer.getByLabel("Body (bn)").fill("প্রাপক {{receiverName}} ({{msisdn}}), হিসাব {{account}}, পিন {{pin}}: {{amount}} টাকা");
    await viewer.getByLabel("Sample msisdn").fill("+8801999888777");
    await expect(preview).toHaveText("প্রাপক K. (+880199****777), হিসাব ****0123, পিন ****: 1500.00 টাকা");
    await expect(preview).toHaveAttribute("lang", "bn");
    await expect(preview).not.toContainText("01999888777");
    await expect(viewer.getByTestId("sms-size")).toContainText("UCS-2");

    await viewer.getByLabel("Body (bn)").fill("{{#amount}}x");
    await expect(viewer.getByTestId("template-problems")).toContainText("body: placeholder {{#amount}} is not a plain {{name}}");
    // a viewer proposes nothing
    await expect(viewer.getByRole("button", { name: "Request publish" })).toHaveCount(0);
    await viewer.context().close();
  });

  test("a maker proposes a Bangla text, the checker approves, the new version is published", async ({ browser }) => {
    await clearPending(browser, `notify_template ${KEY}`);
    const maker = await operator(browser, "maker");
    await maker.getByRole("link", { name: "Templates" }).click();
    const picker = maker.getByRole("form", { name: "Pick template" });
    await picker.getByLabel("Code").fill(CODE);
    await picker.getByLabel("Channel").selectOption("SMS");
    await picker.getByLabel("Locale").selectOption("bn");
    await picker.getByRole("button", { name: "Open" }).click();
    await expect(maker.getByTestId("template-versions")).toContainText("PUBLISHED");

    await maker.getByLabel("Body (bn)").fill(NEW_BODY);
    await expect(maker.getByTestId("template-preview"))
      .toHaveText("0181****321 নম্বর থেকে ৳1500.00 পেয়েছেন। TrxID 0193a1b2-0000-7000-8000-000000000001");
    const { id, version } = await propose(maker, NEW_BODY, "shorter wording (e2e, valid from 2099)");
    await expect(highlightedRow(maker)).toContainText(NEW_BODY);
    await expect(highlightedRow(maker)).toContainText(`notify_template ${KEY}`);

    await asChecker(browser, async (checker) => {
      const decided = await approve(checker, id);
      await expect(decided).toContainText(`published v${version}`);
      await decided.getByRole("link", { name: `notify_template ${KEY}` }).click();
      await expect(versionRow(checker, version)).toContainText("PUBLISHED");
      await expect(checker.getByTestId("template-live")).toHaveText(NEW_BODY);
      await expect(checker.getByRole("region", { name: "Published text" })).toContainText(`v${version}`);
    });
    await maker.context().close();
  });

  test("a rejected version is never live", async ({ browser }) => {
    await clearPending(browser, `notify_template ${KEY}`);
    const maker = await operator(browser, "maker");
    await maker.goto(PAGE);
    const liveBefore = (await maker.getByTestId("template-live").textContent()) ?? "";
    const wrong = "ভুল {{amount}}";
    const { id, version } = await propose(maker, wrong, "test");

    await asChecker(browser, async (checker) => {
      await reject(checker, id, "typo");
      await checker.goto(PAGE);
      await expect(versionRow(checker, version)).toContainText("REJECTED");
      await expect(checker.getByTestId("template-live")).toHaveText(liveBefore);
      await expect(checker.getByTestId("template-live")).not.toHaveText(wrong);
    });
    await maker.context().close();
  });
});
