package mfs.mw.portal.api.entity;

import java.util.Optional;

/**
 * Lifecycle of a bulk batch: upload and maker-checker (ticket 33), then execution (ticket 34):
 * PENDING_APPROVAL -> APPROVED / REJECTED / EXPIRED (72 h without a decision); APPROVED -> EXECUTING
 * (first child sent) <-> PAUSED -> COMPLETED (every row settled) or COMPLETED_WITH_EXCEPTIONS
 * (every row settled or given up, at least one MANUAL_REVIEW or UNRESOLVED; M3 gate decision).
 */
public enum BatchStatus {
  PENDING_APPROVAL,
  APPROVED,
  REJECTED,
  EXPIRED,
  EXECUTING,
  PAUSED,
  COMPLETED,
  COMPLETED_WITH_EXCEPTIONS;

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
