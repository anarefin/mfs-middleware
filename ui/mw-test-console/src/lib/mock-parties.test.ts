import { describe, expect, it } from "vitest";
import { isParty, MOCK_PARTIES, normalCalls, PARTY_NAMES, scenarioBody, switchBody } from "./mock-parties";
import { PRESETS } from "./presets";

describe("mock parties (mock-control API, ADR D33)", () => {
  it("lists the seven parties of mw-simulators", () => {
    expect([...PARTY_NAMES]).toEqual(["ocs", "billagg", "c2wbank", "merchant", "smsc", "ussdgw", "payshield"]);
    expect(isParty("smsc")).toBe(true);
    expect(isParty("nobody")).toBe(false);
    expect(MOCK_PARTIES.filter((p) => p.callbacks).map((p) => p.name)).toEqual(["billagg", "c2wbank"]);
  });

  it("builds a scenario body without the empty fields", () => {
    expect(scenarioBody({ operation: " submit ", behaviour: "BUSINESS_ERROR", code: "0x58", delayMs: "", httpStatus: "", key: "", nextN: "2" }))
      .toEqual({ operation: "submit", behaviour: "BUSINESS_ERROR", code: "0x58", nextN: 2 });
    expect(scenarioBody({ behaviour: "LATE_CALLBACK", delayMs: "20000" })).toEqual({ behaviour: "LATE_CALLBACK", delayMs: 20000 });
  });

  it("builds a switch body, the latency range one value when only the minimum is given", () => {
    expect(switchBody({ state: "SLOW", latencyMinMs: "3000" })).toEqual({ state: "SLOW", latencyMinMs: 3000, latencyMaxMs: 3000 });
    expect(switchBody({})).toEqual({ state: "UP" });
  });

  it("puts a party back to normal with three calls on its own paths", () => {
    expect(normalCalls("ocs").map((c) => `${c.method} ${c.path}`)).toEqual([
      "DELETE /test/mock-control/ocs/scenarios", "PUT /test/mock-control/ocs/switch", "PUT /test/mock-control/ocs/settings",
    ]);
  });

  it("presets use the mock-control API, never the old /sim scenario paths", () => {
    for (const p of PRESETS) {
      for (const c of p.calls) expect(c.path, p.id).not.toMatch(/^\/test\/sim\/[a-z]+\/(scenario|requests)|smpp\/scenario|ipn\/scenario/);
    }
    const reset = PRESETS.find((p) => p.id === "reset-all")!;
    for (const party of PARTY_NAMES) expect(reset.calls.some((c) => c.path === `/test/mock-control/${party}/scenarios`), party).toBe(true);
  });
});
