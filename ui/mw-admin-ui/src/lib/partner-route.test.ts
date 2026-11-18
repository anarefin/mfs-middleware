import { describe, expect, it } from "vitest";
import { parseRouteForm, routeStatusLabel } from "./partner-route";

const ROUTE = `- route:
    from:
      uri: direct:partner-newco
      steps:
        - to: "kamelet:mw-audit"
`;

const good = {
  route: ROUTE,
  allowedHosts: "api.newco.example\n mw-simulators , ",
  mappings: '{"PAY.request": "{\\"ref\\": reference}", "PAY.response": "{\\"outcome\\": \\"SUCCESS\\"}"}',
  dryRunOperation: "PAY",
  dryRunSample: '{"creditParty": {"type": "BILLER_ACCOUNT", "id": "77001234"}}',
  errorMap: '{"E1": {"suffix": "ACCOUNT_CLOSED", "retryable": false}}',
  timeoutMs: "20000",
};

describe("parseRouteForm (ticket 83)", () => {
  it("builds the UPSERT_ROUTE config from the editor fields", () => {
    const r = parseRouteForm(good);
    expect(r).toEqual({
      ok: true,
      config: {
        route: ROUTE,
        allowedHosts: ["api.newco.example", "mw-simulators"],
        mappings: { "PAY.request": '{"ref": reference}', "PAY.response": '{"outcome": "SUCCESS"}' },
        dryRun: { operation: "PAY", sample: { creditParty: { type: "BILLER_ACCOUNT", id: "77001234" } } },
        errorMap: { E1: { suffix: "ACCOUNT_CLOSED", retryable: false } },
        timeoutMs: 20000,
      },
    });
  });

  it("leaves out an empty error map, sample and timeout", () => {
    const r = parseRouteForm({ ...good, errorMap: " ", dryRunSample: "", timeoutMs: "" });
    expect(r.ok && r.config).toEqual({
      route: ROUTE,
      allowedHosts: ["api.newco.example", "mw-simulators"],
      mappings: { "PAY.request": '{"ref": reference}', "PAY.response": '{"outcome": "SUCCESS"}' },
      dryRun: { operation: "PAY", sample: {} },
    });
  });

  it.each([
    [{ route: " " }, "route", "the route YAML"],
    [{ allowedHosts: "" }, "allowedHosts", "at least one host"],
    [{ allowedHosts: "https://x.example/a" }, "allowedHosts", "host names only"],
    [{ mappings: "[1]" }, "mappings", "a JSON object of JSONata strings by name"],
    [{ mappings: '{"PAY.request": 1}' }, "mappings", "a JSON object of JSONata strings by name"],
    [{ dryRunOperation: "REFUND" }, "dryRun.operation", "PAY, INQUIRY or STATUS_QUERY"],
    [{ dryRunSample: "not json" }, "dryRun.sample", "a JSON object"],
    [{ errorMap: '{"E1": {"suffix": "bad suffix"}}' }, "errorMap", "code -> {suffix A-Z0-9_, retryable}"],
    [{ timeoutMs: "0" }, "timeoutMs", "a whole number 1 to 120000"],
  ])("refuses %o", (change, field, issue) => {
    expect(parseRouteForm({ ...good, ...change })).toEqual({ ok: false, field, issue });
  });
});

describe("routeStatusLabel", () => {
  it("says what the adapter made of the version", () => {
    expect(routeStatusLabel(undefined)).toBe("seed (the adapter's own, never published)");
    expect(routeStatusLabel({ version: 2, status: "PENDING_DRY_RUN", problems: [], kamelets: [] })).toBe("v2 waiting for its dry-run");
    expect(routeStatusLabel({ version: 2, status: "LIVE", problems: [], kamelets: [], liveVersion: 2 })).toBe("v2 LIVE");
    expect(routeStatusLabel({ version: 3, status: "REJECTED", problems: ["x"], kamelets: [], liveVersion: 2 }))
      .toBe("v3 REJECTED (v2 still serves)");
  });
});
