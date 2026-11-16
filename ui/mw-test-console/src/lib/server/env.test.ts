import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("server-only", () => ({}));

const { optional, seedManifestPath } = await import("./env");

describe("env.optional", () => {
  afterEach(() => {
    delete process.env.TC_TEST_VALUE;
    delete process.env.SEED_MANIFEST;
  });

  it("is undefined for a missing or empty value and never throws", () => {
    expect(optional("TC_TEST_VALUE")).toBeUndefined();
    process.env.TC_TEST_VALUE = "";
    expect(optional("TC_TEST_VALUE")).toBeUndefined();
  });

  it("returns a set value", () => {
    process.env.TC_TEST_VALUE = "x";
    expect(optional("TC_TEST_VALUE")).toBe("x");
  });

  it("finds the seed manifest at the mounted volume unless SEED_MANIFEST says otherwise", () => {
    expect(seedManifestPath()).toBe("/run/mw/seed/manifest.json");
    process.env.SEED_MANIFEST = "/tmp/m.json";
    expect(seedManifestPath()).toBe("/tmp/m.json");
  });
});
