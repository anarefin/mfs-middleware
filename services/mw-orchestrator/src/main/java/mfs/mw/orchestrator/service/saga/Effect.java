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
  SEND_RELEASE,
  SEND_REVERSE,
  /** QueryTransaction to the core (status inquiry). */
  SEND_QUERY,
  SEND_PARTNER_REQUEST,
  /** Arm the first status-inquiry slot (spec 7.4). */
  SCHEDULE_INQUIRY,
  /**
   * Ask whoever holds the unknown outcome (spec 7.4): the partner (STATUS_QUERY) for a flow with a
   * partner leg, else the core (QueryTransaction).
   */
  SEND_INQUIRY
}
