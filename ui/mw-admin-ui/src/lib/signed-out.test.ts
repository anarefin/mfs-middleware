import { beforeEach, describe, expect, it } from "vitest";
import { clearSignedOut, isSignedOut, markSignedOut } from "./signed-out";

describe("signed-out sessions", () => {
  beforeEach(() => clearSignedOut());

  it("knows a session signed out until its cookie could have expired", () => {
    markSignedOut("s-1", 1_000, 0);
    expect(isSignedOut("s-1", 999)).toBe(true);
    expect(isSignedOut("s-1", 1_000)).toBe(false);
  });

  it("does not touch other sessions", () => {
    markSignedOut("s-1", 1_000, 0);
    expect(isSignedOut("s-2", 0)).toBe(false);
  });

  it("ignores a token without a session id", () => {
    markSignedOut(undefined, 1_000, 0);
    expect(isSignedOut(undefined, 0)).toBe(false);
  });

  it("forgets expired entries when a new one is added", () => {
    markSignedOut("old", 10, 0);
    markSignedOut("new", 1_000, 20);
    expect(isSignedOut("old", 20)).toBe(false);
    expect(isSignedOut("new", 20)).toBe(true);
  });
});
