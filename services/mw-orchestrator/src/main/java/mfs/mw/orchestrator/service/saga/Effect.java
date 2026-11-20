package mfs.mw.orchestrator.service.saga;

/**
 * What the service must do after a transition (spec 7.1: each step sends a command). The machine
 * only names it; the service performs it. Steps whose flow is not wired yet are logged and
 * skipped by the service.
 */
public enum Effect {
  NONE,
  SEND_VALIDATE,
  SEND_HOLD,
  SEND_POST,
  SEND_COMMIT,
  /**
   * CreditWallet of a forced credit, once the core confirmed the txn is not credited (part of the
   * commit step; {@link #SEND_COMMIT} of a forced credit asks the core first).
   */
  SEND_CREDIT,
  SEND_RELEASE,
  SEND_REVERSE,
  /**
   * Partner {@code REVERSE} after a refused commit (ticket 96b). The same request id on every
   * resend. Not the core ReverseTransaction of a completed transaction.
   */
  SEND_PARTNER_REVERSE,
  /**
   * QueryTransaction asking whether the hold of a refused commit still exists, once the partner
   * has confirmed REVERSE (ticket 96b).
   */
  SEND_HOLD_QUERY,
  /** ReleaseHold of a hold that the core says is still there after the partner confirmed REVERSE. */
  SEND_COMPENSATING_RELEASE,
  /** QueryTransaction asking whether a REVERSING transaction was reversed (part of the reverse step). */
  SEND_REVERSAL_QUERY,
  /** QueryTransaction to the core (status inquiry). */
  SEND_QUERY,
  SEND_PARTNER_REQUEST,
  /** Arm the first status-inquiry slot (spec 7.4). */
  SCHEDULE_INQUIRY,
  /**
   * Ask whoever holds the unknown outcome (spec 7.4): the partner (STATUS_QUERY) for a flow with a
   * partner leg, else the core (QueryTransaction).
   */
  SEND_INQUIRY,
  /** BalanceInquiry to the core (BALANCE, ADR D26); moves no money. */
  SEND_BALANCE_INQUIRY
}
