import { describe, expect, it } from "vitest";
import { activeNav } from "./nav";

describe("nav", () => {
  it("marks the page's section; Home only for /", () => {
    expect(activeNav("/")?.label).toBe("Home");
    expect(activeNav("/ussd")?.label).toBe("USSD phone");
    expect(activeNav("/simulators/x")?.label).toBe("Simulators & faults");
    expect(activeNav("/nowhere")).toBeUndefined();
  });
});
