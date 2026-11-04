package mfs.mw.portal.api.entity;

/**
 * Lifecycle of a bulk batch: upload and maker-checker (ticket 33), then execution (ticket 34):
 * APPROVED -> EXECUTING (first child sent) <-> PAUSED -> COMPLETED (every row settled).
 */
public enum BatchStatus {
  PENDING_APPROVAL,
  APPROVED,
  REJECTED,
  EXECUTING,
  PAUSED,
  COMPLETED;

  /** Whether the batch was approved, so it has children and a result file. */
  public boolean approved() {
    return this == APPROVED || this == EXECUTING || this == PAUSED || this == COMPLETED;
  }
}
