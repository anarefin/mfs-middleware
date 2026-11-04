package mfs.mw.orchestrator.service.saga;

/** The 14 saga states of spec 7.2; the names are the {@code status} values on {@code mw.txn.status.v1}. */
public enum Status {
  RECEIVED,
  VALIDATING,
  HOLDING,
  POSTING,
  PARTNER_PENDING,
  UNKNOWN,
  COMMITTING,
  RELEASING,
  REVERSING,
  COMPLETED,
  FAILED,
  REJECTED,
  REVERSED,
  MANUAL_REVIEW;

  /** COMPLETED, FAILED, REJECTED and REVERSED (REVERSING can still follow COMPLETED). */
  public boolean terminal() {
    return this == COMPLETED || this == FAILED || this == REJECTED || this == REVERSED;
  }
}
