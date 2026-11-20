package mfs.mw.orchestrator.service.saga;

/**
 * The compensation a transaction is waiting on (tickets 96b, 96c). Stored on
 * {@code transaction.pending_leg}. A core reversal of a completed transaction that has no held
 * partner leg leaves the column null. A completed top-up or bill payment sets
 * {@link #PARTNER_REVERSE} until the partner confirms, then clears it so the core reversal runs.
 * The timer step stays the status name; this column is the leg.
 */
public enum PendingLeg {
  /**
   * Partner {@code REVERSE} not yet confirmed: after a refused commit (ticket 96b), or while
   * reversing a completed top-up or bill payment (ticket 96c).
   */
  PARTNER_REVERSE,
  /** Partner confirmed the reverse; waiting for the core to say whether the hold remains. */
  HOLD_QUERY,
  /** The hold remains; waiting for ReleaseHold. */
  HOLD_RELEASE
}
