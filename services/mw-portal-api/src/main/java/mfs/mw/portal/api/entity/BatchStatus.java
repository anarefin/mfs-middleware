package mfs.mw.portal.api.entity;

import java.util.Optional;

/**
 * Lifecycle of a bulk batch: upload and maker-checker (ticket 33), then execution (ticket 34):
 * PREVIEW (uploaded, rows checked; ticket 129b) -> PENDING_APPROVAL (submitted by its maker) or
 * EXPIRED; PENDING_APPROVAL -> APPROVED / REJECTED / EXPIRED (72 h after the upload without a
 * decision); APPROVED -> EXECUTING
 * (first child sent) <-> PAUSED -> COMPLETED (every row settled) or COMPLETED_WITH_EXCEPTIONS
 * (every row settled or given up, at least one MANUAL_REVIEW or UNRESOLVED; M3 gate decision).
 * BLOCKED_FUNDS (ticket 129c): the org wallet held less than the batch at approval or when the
 * dispatcher would have started it; nothing was sent, a checker approves it again.
 */
public enum BatchStatus {
  PREVIEW,
  PENDING_APPROVAL,
  APPROVED,
  REJECTED,
  EXPIRED,
  EXECUTING,
  PAUSED,
  COMPLETED,
  COMPLETED_WITH_EXCEPTIONS,
  BLOCKED_FUNDS;

  /** Whether the batch was approved, so it has children and a result file. */
  public boolean approved() {
    return this == APPROVED
        || this == EXECUTING
        || this == PAUSED
        || this == COMPLETED
        || this == COMPLETED_WITH_EXCEPTIONS;
  }

  /** The status named {@code name}, or empty for an unknown name. */
  public static Optional<BatchStatus> parse(String name) {
    for (BatchStatus s : values()) {
      if (s.name().equals(name)) {
        return Optional.of(s);
      }
    }
    return Optional.empty();
  }
}
