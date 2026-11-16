import { describe, expect, it } from "vitest";
import { cacheDecision, fromTokenResponse, totpStep, totpWaitMs } from "./token-cache";

describe("token cache", () => {
  const now = 1_000_000_000_000;
  const t = fromTokenResponse({ access_token: "a", refresh_token: "r", expires_in: 300, refresh_expires_in: 1800 }, now);

  it("stores the expiries in ms from now", () => {
    expect(t).toEqual({ access: "a", refresh: "r", exp: now + 300_000, rexp: now + 1_800_000 });
    expect(fromTokenResponse({ access_token: "a", expires_in: 60 }, now)).toEqual({ access: "a", refresh: undefined, exp: now + 60_000, rexp: now });
  });

  it("uses the token until 30 s before it expires, then refreshes, then logs in again", () => {
    expect(cacheDecision(undefined, now)).toBe("login");
    expect(cacheDecision(t, now)).toBe("use");
    expect(cacheDecision(t, now + 269_000)).toBe("use");
    expect(cacheDecision(t, now + 271_000)).toBe("refresh");
    expect(cacheDecision(t, now + 1_771_000)).toBe("login");
    expect(cacheDecision({ ...t, refresh: undefined }, now + 271_000)).toBe("login");
  });
});

describe("one TOTP step per user", () => {
  const step0 = 1_000_000_020_000; // 20 s into its 30 s step
  it("never waits for a user without a used step or with an older one", () => {
    expect(totpWaitMs(undefined, step0)).toBe(0);
    expect(totpWaitMs(totpStep(step0) - 1, step0)).toBe(0);
  });

  it("waits for the next step (plus 200 ms) when this step's code was used", () => {
    expect(totpWaitMs(totpStep(step0), step0)).toBe(30_000 - (step0 % 30_000) + 200);
  });
});
