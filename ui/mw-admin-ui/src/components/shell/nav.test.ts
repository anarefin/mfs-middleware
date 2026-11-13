import { describe, expect, it } from "vitest";
import { activeNav, paletteTargets } from "./nav";

describe("nav", () => {
  it("marks the section of nested pages", () => {
    expect(activeNav("/transactions/0193a1b2-0000-7000-8000-000000000001")?.label).toBe("Transactions");
    expect(activeNav("/partners")?.label).toBe("Partners");
    expect(activeNav("/login")).toBeUndefined();
  });
  it("turns a typed value into a destination", () => {
    expect(paletteTargets("")).toEqual([]);
    expect(paletteTargets("0193a1b2-0000-7000-8000-000000000001")[0]!.href).toBe("/transactions/0193a1b2-0000-7000-8000-000000000001");
    expect(paletteTargets("0171 2000 001")[0]!.href).toBe("/transactions?msisdn=01712000001");
    expect(paletteTargets("DESCO-55120")[0]!.href).toBe("/transactions?partnerRef=DESCO-55120");
  });
});
