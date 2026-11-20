// Ticket 131c: partner tabs against the real mw-admin-api. Maintenance, the mock/real endpoint and the
// error map are ops-maker / ops-checker (the seeded personas). Credential upload is integration-maker
// only, so this spec checks that an ops-maker sees metadata and no secret field; the upload form's
// clear-and-don't-echo behaviour is the Vitest credential-form test. The live run is scripts/ui-e2e.sh.
import { expect, test, type Browser, type Page } from "@playwright/test";

import { approve, asChecker, clearPending, filedRequestId, highlightedRow } from "./support/approvals";
import { operator } from "./support/session";

const PARTNER = "ocs";
const MOCK = "http://mw-simulators:8080";
const REAL = "http://mw-simulators:8080/ocs";

async function file(maker: Page, button: string): Promise<string> {
  await maker.getByRole("button", { name: button }).click();
  return filedRequestId(maker);
}

test.describe("partner credentials", { tag: ["@partners", "@credentials"] }, () => {
  test("a maker sets a past maintenance window, the checker approves, and it is left in the past", async ({ browser }) => {
    await clearPending(browser, `partner ${PARTNER}`);
    const maker = await operator(browser, "maker");
    await maker.goto(`/console/partners/${PARTNER}?tab=maintenance`);
    await expect(maker.getByTestId("maintenance-state")).toBeVisible();

    const form = maker.getByRole("form", { name: "Maintenance" });
    await form.getByLabel("Start (UTC)").fill("2026-12-01T06:00");
    await form.getByLabel("End (UTC)").fill("2026-12-01T00:00");
    await form.getByLabel("Reason").fill("end before start");
    await form.getByRole("button", { name: "Request maintenance on" }).click();
    await expect(form.locator(".error[role=alert]")).toContainText("MW-VAL-001");
    await expect(form.locator(".error[role=alert]")).toContainText("must be after start");

    const message = `e2e ${Date.now()}`;
    await setWindow(browser, maker, message);
    await setWindow(browser, maker, "e2e window idle");
    await expect(maker.getByTestId("maintenance-state")).toHaveText("off");
    await expect(maker.getByTestId("maintenance-window")).toContainText("e2e window idle");
    await maker.context().close();
  });

  test("a maker switches the endpoint profile and the checker approves, then it is put back", async ({ browser }) => {
    await clearPending(browser, `partner ${PARTNER}`);
    const maker = await operator(browser, "maker");
    await maker.goto(`/console/partners/${PARTNER}?tab=endpoint`);
    const before = (await maker.getByTestId("endpoint-profile").textContent())?.trim();
    const next = before === "REAL" ? "MOCK" : "REAL";
    const back = before === "REAL" || before === "MOCK" ? before : "MOCK";

    const form = maker.getByRole("form", { name: "Endpoint" });
    await form.getByLabel("MOCK URL").fill("http://evil.example/v1");
    await form.getByLabel("REAL URL").fill(REAL);
    await form.getByLabel("Reason").fill("host not allowed");
    await form.getByRole("button", { name: "Request endpoint" }).click();
    await expect(form.locator(".error[role=alert]")).toContainText("MW-VAL-001");
    await expect(form.locator(".error[role=alert]")).toContainText("its host must be in allowedHosts");

    await switchEndpoint(browser, maker, next);
    await switchEndpoint(browser, maker, back);
    await expect(maker.getByTestId("endpoint-profile")).toHaveText(back);
    await expect(maker.getByTestId("endpoint-urls")).toContainText(MOCK);
    await maker.context().close();
  });

  test("a maker replaces the error map and puts the seeded line back", async ({ browser }) => {
    await clearPending(browser, `error map ${PARTNER}`);
    const maker = await operator(browser, "maker");
    await maker.goto(`/console/partners/${PARTNER}?tab=error-codes`);
    const form = maker.getByRole("form", { name: "Error codes" });
    const original = await form.getByLabel("Error map").inputValue();
    const base = original.split("\n").filter((line) => !line.includes("E2E_ONLY")).join("\n");

    await form.getByLabel("Error map").fill("2001 bad-suffix");
    await form.getByLabel("Reason").fill("bad suffix");
    await form.getByRole("button", { name: "Request error map" }).click();
    await expect(form.locator(".error[role=alert]")).toContainText("MW-VAL-001");
    await expect(form.locator(".error[role=alert]")).toContainText("suffix is A-Z, 0-9 and _");

    await replaceMap(browser, maker, `${base}${base ? "\n" : ""}E2E1 E2E_ONLY`);
    await expect(maker.getByTestId("error-map")).toContainText("E2E_ONLY");
    await replaceMap(browser, maker, base);
    await expect(maker.getByTestId("error-map")).not.toContainText("E2E_ONLY");
    await maker.context().close();
  });

  test("credentials are metadata and the expiring card is on Partners; an ops-maker cannot upload", async ({ browser }) => {
    const maker = await operator(browser, "maker");
    await maker.goto("/console/partners");
    await expect(maker.getByRole("region", { name: "Credentials expiring" })).toBeVisible();
    await maker.goto(`/console/partners/${PARTNER}?tab=credentials`);
    await expect(maker.getByRole("region", { name: "Credentials" })).toBeVisible();
    await expect(maker.getByText("An integration-maker uploads a credential")).toBeVisible();
    await expect(maker.locator("input[type=password]")).toHaveCount(0);
    await expect(maker.getByRole("button", { name: "Upload credential" })).toHaveCount(0);

    const viewer = await operator(browser, "viewer");
    await viewer.goto(`/console/partners/${PARTNER}?tab=maintenance`);
    await expect(viewer.getByRole("link", { name: "Endpoint (mock/real)" })).toBeVisible();
    await expect(viewer.getByRole("link", { name: "Error codes" })).toBeVisible();
    await expect(viewer.getByRole("button", { name: "Request maintenance on" })).toHaveCount(0);
    await viewer.context().close();
    await maker.context().close();
  });
});

async function setWindow(browser: Browser, maker: Page, message: string): Promise<void> {
  await maker.goto(`/console/partners/${PARTNER}?tab=maintenance`);
  const form = maker.getByRole("form", { name: "Maintenance" });
  await form.getByLabel("Start (UTC)").fill("2020-01-01T00:00");
  await form.getByLabel("End (UTC)").fill("2020-01-02T00:00");
  await form.getByLabel("Message (en)").fill(message);
  await form.getByLabel("Reason").fill("past maintenance window (e2e)");
  const id = await file(maker, "Request maintenance off");
  await expect(highlightedRow(maker)).toContainText("SET_MAINTENANCE");
  await expect(highlightedRow(maker)).toContainText(`partner ${PARTNER}`);
  await asChecker(browser, async (checker) => {
    await approve(checker, id);
  });
  await maker.goto(`/console/partners/${PARTNER}?tab=maintenance`);
  await expect(maker.getByTestId("maintenance-window")).toContainText(message);
}

async function switchEndpoint(browser: Browser, maker: Page, profile: string): Promise<void> {
  await maker.goto(`/console/partners/${PARTNER}?tab=endpoint`);
  const form = maker.getByRole("form", { name: "Endpoint" });
  await form.getByLabel("Profile").selectOption(profile);
  await form.getByLabel("MOCK URL").fill(MOCK);
  await form.getByLabel("REAL URL").fill(REAL);
  await form.getByLabel("Reason").fill(`endpoint ${profile} (e2e)`);
  const id = await file(maker, "Request endpoint");
  await expect(highlightedRow(maker)).toContainText("SET_ENDPOINT");
  await asChecker(browser, async (checker) => {
    await approve(checker, id);
  });
  await maker.goto(`/console/partners/${PARTNER}?tab=endpoint`);
  await expect(maker.getByTestId("endpoint-profile")).toHaveText(profile);
}

async function replaceMap(browser: Browser, maker: Page, text: string): Promise<void> {
  await maker.goto(`/console/partners/${PARTNER}?tab=error-codes`);
  const form = maker.getByRole("form", { name: "Error codes" });
  await form.getByLabel("Error map").fill(text);
  await form.getByLabel("Reason").fill("error map (e2e)");
  const id = await file(maker, "Request error map");
  await expect(highlightedRow(maker)).toContainText("SET_ERROR_MAP");
  await expect(highlightedRow(maker)).toContainText(`error map ${PARTNER}`);
  await asChecker(browser, async (checker) => {
    await approve(checker, id);
  });
  await maker.goto(`/console/partners/${PARTNER}?tab=error-codes`);
}
