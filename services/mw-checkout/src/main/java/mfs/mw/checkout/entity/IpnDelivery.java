package mfs.mw.checkout.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * One IPN attempt, a row of {@code checkout.ipn_delivery} (spec 9.3, 16.2; ticket 32). PENDING
 * until it is tried, then DELIVERED (2xx) or FAILED (anything else; the next attempt, if any, is
 * its own row). Never leaves the service layer.
 */
public record IpnDelivery(
    UUID deliveryId,
    UUID sessionId,
    String merchantId,
    int attempt,
    String ipnUrl,
    String payload,
    Status status,
    Instant dueAt,
    Instant attemptedAt,
    Integer httpStatus,
    String error,
    Long durationMs,
    Instant createdAt) {

  public enum Status {
    PENDING,
    DELIVERED,
    FAILED
  }

  /** The attempt after this one, due at {@code dueAt}, with the same URL and body. */
  public IpnDelivery next(UUID id, Instant dueAt, Instant now) {
    return new IpnDelivery(
        id, sessionId, merchantId, attempt + 1, ipnUrl, payload, Status.PENDING, dueAt, null, null, null, null, now);
  }
}
