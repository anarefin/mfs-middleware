import { describe, expect, it } from "vitest";
import { anchorBadge } from "./audit-anchor";

describe("audit anchor status (ticket 74)", () => {
  it("shows an intact anchor chain with its newest day", () => {
    expect(anchorBadge({ status: "INTACT", lastAnchorDate: "2026-11-13", verifiedAt: "2026-11-14T00:30:00Z" })).toEqual({
      tone: "good",
      label: "Anchors intact (signed to 2026-11-13)",
    });
  });

  it("flags a broken anchor chain", () => {
    expect(anchorBadge({ status: "BROKEN", lastAnchorDate: "2026-11-04" })).toEqual({
      tone: "bad",
      label: "Anchor check failed (signed to 2026-11-04)",
    });
  });

  it("says when the anchors were not checked, are off, or the status is missing", () => {
    expect(anchorBadge({ status: "UNVERIFIED" })).toEqual({ tone: "warn", label: "Anchors not verified yet" });
    expect(anchorBadge({ status: "OFF" })).toEqual({ tone: "neutral", label: "Anchoring off" });
    expect(anchorBadge(undefined)).toEqual({ tone: "neutral", label: "Anchor status unknown" });
  });
});
