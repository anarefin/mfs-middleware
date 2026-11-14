import type { AuditAnchor } from "./types";

export type AnchorBadge = { tone: "good" | "bad" | "warn" | "neutral"; label: string };

/** The audit view's badge for mw-audit-dw's signed daily anchors (ticket 74). */
export function anchorBadge(anchor: AuditAnchor | undefined): AnchorBadge {
  const to = anchor?.lastAnchorDate ? ` (signed to ${anchor.lastAnchorDate})` : "";
  switch (anchor?.status) {
    case "INTACT":
      return { tone: "good", label: `Anchors intact${to}` };
    case "BROKEN":
      return { tone: "bad", label: `Anchor check failed${to}` };
    case "UNVERIFIED":
      return { tone: "warn", label: "Anchors not verified yet" };
    case "OFF":
      return { tone: "neutral", label: "Anchoring off" };
    default:
      return { tone: "neutral", label: "Anchor status unknown" };
  }
}
