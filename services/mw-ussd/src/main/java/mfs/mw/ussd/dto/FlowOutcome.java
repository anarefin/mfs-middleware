package mfs.mw.ussd.dto;

/**
 * How a flow ended within the synchronous budget.
 *
 * @param kind COMPLETED, FAILED (with the status's or mw-auth's error), PROCESSING (no final answer
 *     within the 8 s budget), UNAVAILABLE (nothing was accepted), WRONG_PIN (mw-auth MW-AUTH-040:
 *     ask for the PIN again; nothing was published) or LOCKED (mw-auth MW-AUTH-041/042/043: the
 *     error's code says which; nothing was published)
 * @param triesLeft WRONG_PIN only: the wrong PINs left before mw-auth locks, when it said so
 */
public record FlowOutcome(Kind kind, String txnId, ErrorInfo error, String triesLeft) {

  public enum Kind {
    COMPLETED,
    FAILED,
    PROCESSING,
    UNAVAILABLE,
    WRONG_PIN,
    LOCKED
  }

  public FlowOutcome(Kind kind, String txnId, ErrorInfo error) {
    this(kind, txnId, error, null);
  }
}
