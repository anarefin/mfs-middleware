import { describe, expect, it } from "vitest";
import { NAV, activeNav } from "./nav";

describe("nav", () => {
  it("marks the page's section; Home only for /", () => {
    expect(activeNav("/")?.label).toBe("Home");
    expect(activeNav("/ussd")?.label).toBe("USSD phone");
    expect(activeNav("/auth")?.label).toBe("Wallet auth");
    expect(activeNav("/simulators/x")?.label).toBe("Simulators & faults");
    expect(activeNav("/files")?.label).toBe("Files (AML, MT940, DW)");
    expect(activeNav("/credentials")?.label).toBe("Logins & secrets");
    expect(activeNav("/seed-data")?.label).toBe("Seed data");
    expect(activeNav("/transactions")?.label).toBe("Transactions");
    expect(activeNav("/nowhere")).toBeUndefined();
  });

  it("puts the logins page right after Home", () => {
    expect(NAV.slice(0, 2).map((n) => n.href)).toEqual(["/", "/credentials"]);
    expect(NAV.map((n) => n.href)).toEqual(expect.arrayContaining(["/seed-data", "/transactions"]));
    expect(new Set(NAV.map((n) => n.href)).size).toBe(NAV.length);
  });
});
