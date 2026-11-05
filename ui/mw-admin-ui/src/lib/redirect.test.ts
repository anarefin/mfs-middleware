import { describe, expect, it } from "vitest";
import { consolePath, safeCallback } from "./redirect";

describe("safeCallback", () => {
  it("returns to a console page after login", () => {
    expect(safeCallback("/transactions?msisdn=017", "/console")).toBe("/console/transactions?msisdn=017");
  });

  it("never sends the operator off-site or back to the login page", () => {
    for (const bad of ["https://evil.example/", "//evil.example", "/\\evil.example", "javascript:alert(1)", "", undefined, "/login"]) {
      expect(safeCallback(bad, "/console")).toBe("/console/transactions");
    }
  });
});

describe("consolePath", () => {
  it("gives the path inside the console whether or not the basePath is still on it", () => {
    expect(consolePath("/console/transactions", "/console")).toBe("/transactions");
    expect(consolePath("/transactions", "/console")).toBe("/transactions");
    expect(consolePath("/console", "/console")).toBe("/");
    expect(consolePath("/consoleX", "/console")).toBe("/consoleX");
  });
});
