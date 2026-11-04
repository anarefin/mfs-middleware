package mfs.mw.portal.api.entity;

import java.util.Optional;

/**
 * State of one row of a batch (ticket 34): PENDING until the dispatcher sends its child, SUBMITTED
 * while the child runs, then the child's settled state as the orchestrator reports it on {@code
 * mw.txn.status.v1}. MANUAL_REVIEW counts as settled for the batch (operations decide it) but may
 * still move on; FAILED, REJECTED and REVERSED are final; COMPLETED only moves on to REVERSED.
 */
public enum ItemStatus {
  PENDING,
  SUBMITTED,
  COMPLETED,
  FAILED,
  REJECTED,
  REVERSED,
  MANUAL_REVIEW;

  /** The row state a child's status record settles the row in; empty for in-progress states. */
  public static Optional<ItemStatus> settledBy(String txnStatus) {
    return switch (txnStatus == null ? "" : txnStatus) {
      case "COMPLETED" -> Optional.of(COMPLETED);
      case "FAILED" -> Optional.of(FAILED);
      case "REJECTED" -> Optional.of(REJECTED);
      case "REVERSED" -> Optional.of(REVERSED);
      case "MANUAL_REVIEW" -> Optional.of(MANUAL_REVIEW);
      default -> Optional.empty();
    };
  }
}
