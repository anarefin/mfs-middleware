package mfs.mw.orchestrator.service.saga;

/**
 * The compensation a transaction is waiting on (ticket 96b). Stored on {@code transaction.pending_leg}
 * so a refused commit's partner reversal is not the core reversal of a completed transaction, which
 * leaves the column null. The timer step stays the status name; this column is the leg.
 */
public enum PendingLeg {
  /** Partner {@code REVERSE} sent after a refused commit, not yet confirmed. */
  PARTNER_REVERSE,
  /** Partner confirmed the reverse; waiting for the core to say whether the hold remains. */
  HOLD_QUERY,
  /** The hold remains; waiting for ReleaseHold. */
  HOLD_RELEASE
}
