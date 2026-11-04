package mfs.mw.ussd.dto;

/**
 * How a flow ended within the synchronous budget.
 *
 * @param kind COMPLETED, FAILED (with the status's error), PROCESSING (no final answer within the
 *     8 s budget) or UNAVAILABLE (nothing was accepted)
 */
public record FlowOutcome(Kind kind, String txnId, ErrorInfo error) {

  public enum Kind {
    COMPLETED,
    FAILED,
    PROCESSING,
    UNAVAILABLE
  }
}
