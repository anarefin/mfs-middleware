package mfs.mw.portal.api.entity;

import java.util.Optional;

/**
 * State of one row of a batch (ticket 34): PENDING until the dispatcher sends its child, SUBMITTED
 * while the child runs, then the child's settled state as the orchestrator reports it on {@code
 * mw.txn.status.v1} (or the reconcile job reads it from the internal query API). MANUAL_REVIEW
 * counts as settled for the batch (operations decide it) but may still move on; FAILED, REJECTED and
 * REVERSED are final; COMPLETED only moves on to REVERSED. UNRESOLVED is set by the reconcile job
 * when a SUBMITTED child has no settled state long after it was sent; a later record still settles
 * it.
 *
 * <p>Which orchestrator states settle a row is said here once ({@link #settledBy}); no other code
 * compares status strings.
 */
public enum ItemStatus {
  PENDING(false, false),
  SUBMITTED(false, false),
  COMPLETED(true, false),
  FAILED(true, false),
  REJECTED(true, false),
  REVERSED(true, false),
  MANUAL_REVIEW(true, true),
  UNRESOLVED(false, true);

  /** The orchestrator's state of the same name settles the row in this state. */
  private final boolean settledByTxn;

  /** The row makes its batch end COMPLETED_WITH_EXCEPTIONS. */
  private final boolean exception;

  ItemStatus(boolean settledByTxn, boolean exception) {
    this.settledByTxn = settledByTxn;
    this.exception = exception;
  }

  public boolean exception() {
    return exception;
  }

  /** The row state a child's transaction status settles the row in; empty for in-progress states. */
  public static Optional<ItemStatus> settledBy(String txnStatus) {
    for (ItemStatus s : values()) {
      if (s.settledByTxn && s.name().equals(txnStatus)) {
        return Optional.of(s);
      }
    }
    return Optional.empty();
  }
}
