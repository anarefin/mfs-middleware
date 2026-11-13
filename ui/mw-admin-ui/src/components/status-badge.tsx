import type { ComponentProps } from "react";
import { Badge } from "@/components/ui/badge";

type Tone = "neutral" | "good" | "bad" | "warn" | "info" | "rev";

// Transaction, change-request, version and partner states -> colour. The badge text stays the
// raw code (the e2e suites and operators read COMPLETED, PENDING ... as the API sends them).
const TONES: Record<string, Tone> = {
  COMPLETED: "good", APPROVED: "good", PUBLISHED: "good", ENABLED: "good", ACTIVE: "good",
  FAILED: "bad", REJECTED: "bad", DISABLED: "bad", LOCKED: "bad",
  MANUAL_REVIEW: "warn", UNKNOWN: "warn", EXPIRED: "neutral", SUPERSEDED: "neutral", DRAFT: "neutral",
  PENDING: "info", PROCESSING: "info", PAID: "good", COMPLETED_WITH_EXCEPTIONS: "warn", RECEIVED: "info", VALIDATING: "info", HOLDING: "info", POSTING: "info",
  PARTNER_PENDING: "info", COMMITTING: "info", RELEASING: "info",
  REVERSING: "rev", REVERSED: "rev",
};

export function statusTone(status: string | undefined): Tone {
  return TONES[(status ?? "").toUpperCase()] ?? "neutral";
}

export function StatusBadge({ status, children, ...props }: Omit<ComponentProps<"span">, "children"> & { status: string | undefined; children?: React.ReactNode }) {
  return (
    <Badge tone={statusTone(status)} dot {...props}>
      {children ?? status}
    </Badge>
  );
}
