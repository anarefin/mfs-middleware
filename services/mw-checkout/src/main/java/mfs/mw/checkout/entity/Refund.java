package mfs.mw.checkout.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * One merchant refund, a row of {@code checkout.refund} (ticket 116): its own REVERSAL transaction
 * ({@code refundId} = its txnId) of the session's payment. Never leaves the service layer.
 */
public record Refund(
    UUID refundId,
    UUID sessionId,
    String merchantId,
    String idemKey,
    String requestHash,
    long amountMinor,
    String currency,
    UUID originalTxnId,
    String reason,
    Status status,
    String errorCode,
    Instant createdAt,
    Instant updatedAt) {

  public enum Status {
    PENDING,
    COMPLETED,
    REJECTED
  }
}
