// Ticket 83 (ADR D28): a partner route as config through maker-checker against the real stack. The
// maker pastes a route for the billpay partner `e2eroute` (onboarding on the first run, a new version
// afterwards); a route with a forbidden construct is refused at submit with its line (MW-VAL-041); the
// checker approves; mw-adapter-billpay dry-runs it against mw-simulators' programmable stub
// (/sim/partners/e2eroute/**, set here through the simulator's control API) and reports LIVE, which
// the partner page shows. The route calls mw-simulators itself (a dev host), auth none.
import { expect, test } from "@playwright/test";

import { approve, asChecker, clearPending, filedRequestId } from "./support/approvals";
import { operator } from "./support/session";

const PARTNER = "e2eroute";
const SIMULATORS = (process.env.E2E_SIMULATORS_URL ?? `http://127.0.0.1:${process.env.MW_PORT_SIMULATORS ?? "8085"}`).replace(/\/+$/, "");

const ROUTE = `- route:
    from:
      uri: direct:partner-${PARTNER}
      steps:
        - to: "kamelet:mw-map?mapping=PAY.request"
        - to: "kamelet:mw-http-call?url=http://mw-simulators:8080&method=POST&path=/v1/pay"
        - to: "kamelet:mw-classify?mapping=PAY.response&definiteStatuses=422"
        - to: "kamelet:mw-audit"
`;
const MAPPINGS = JSON.stringify({
  "PAY.request": "{\"ref\": reference, \"account\": creditParty.id}",
  "PAY.response": "status = 200 ? {\"outcome\": \"SUCCESS\", \"partnerRef\": body.receipt} : {\"outcome\": \"UNKNOWN\", \"reason\": \"UNEXPECTED_STATUS\"}",
});

/** The dry-run target: the stub answers the PAY the route sends with a receipt. */
async function stubDryRun(): Promise<void> {
  const r = await fetch(`${SIMULATORS}/sim/stubs/${PARTNER}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ operation: "PAY", method: "POST", path: "/v1/pay", status: 200, body: { receipt: "E2E-1" } }),
  });
  expect(r.status, "mw-simulators control API (MW_PORT_SIMULATORS)").toBe(200);
}

test.describe("partner routes", { tag: "@partners" }, () => {
  test("a maker submits a route, a checker approves, the adapter's dry-run makes it LIVE", async ({ browser }) => {
    await stubDryRun();
    await clearPending(browser, `partner ${PARTNER}`);
    const maker = await operator(browser, "maker");
    await maker.goto(`/console/partners/${PARTNER}`);
    const form = maker.getByRole("form", { name: "Partner route" });
    await form.getByLabel("Allowed hosts (one per line)").fill("mw-simulators");
    await form.getByLabel("Mappings (JSON: name -> JSONata)").fill(MAPPINGS);
    await form.getByLabel("Dry-run sample (JSON: the canonical call input)")
      .fill(JSON.stringify({ creditParty: { type: "BILLER_ACCOUNT", id: "77001234" } }));
    await form.getByLabel("Reason").fill("onboard a biller with a route (e2e)");

    // a raw endpoint is refused before any approval, with its line
    await form.getByLabel("Route (Camel YAML DSL)").fill(ROUTE.replace('- to: "kamelet:mw-map?mapping=PAY.request"', '- to: "exec:rm"'));
    await form.getByRole("button", { name: "Submit route" }).click();
    await expect(form.locator(".error[role=alert]")).toContainText("MW-VAL-041");
    await expect(form.locator(".error[role=alert]")).toContainText("line 5");

    // the good route (each run a new version: the classify statuses change)
    const version = String(Date.now() % 1000);
    await form.getByLabel("Route (Camel YAML DSL)").fill(ROUTE.replace("definiteStatuses=422", `definiteStatuses=422,${400 + (Number(version) % 99)}`));
    await form.getByRole("button", { name: "Submit route" }).click();
    const id = await filedRequestId(maker);

    await asChecker(browser, async (checker) => {
      const decided = await approve(checker, id);
      await expect(decided).toContainText("published v");
      await checker.goto(`/console/partners/${PARTNER}`);
      await expect(async () => {
        await checker.reload();
        await expect(checker.getByTestId("route-status")).toHaveText(/LIVE$/);
      }).toPass({ timeout: 30_000 });
      await expect(checker.getByTestId("route-yaml")).toContainText(`direct:partner-${PARTNER}`);
    });
    await maker.context().close();
  });
});
