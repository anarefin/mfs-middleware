package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The core call of a reversal step. {@code refund} is the amount of a merchant refund (ticket 116b),
 * sent to the core as the reversal's {@code amount}; null for a full reversal (the whole original).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReversePayload(String originalTxnId, Amount amount, Amount fee, String reason, Amount refund) {

  /** A full reversal: the core reverses the whole original. */
  public ReversePayload(String originalTxnId, Amount amount, Amount fee, String reason) {
    this(originalTxnId, amount, fee, reason, null);
  }
}
