package mfs.mw.notify.entity;

import java.time.Instant;
import mfs.mw.notify.util.Sensitive;
import mfs.mw.notify.util.SensitiveType;

/** A row of {@code notify.message} (spec 10, 16.2). */
public record MessageRecord(
    String messageId,
    String txnId,
    String correlationId,
    String channel,
    @Sensitive(SensitiveType.MSISDN) String recipientMsisdn,
    String templateCode,
    Integer templateVersion,
    String locale,
    String priority,
    String senderId,
    // Rendered text (params masked); not logged.
    @Sensitive(SensitiveType.SECRET) String body,
    Integer concatRef,
    Integer segmentCount,
    String status,
    int attempts,
    Instant nextAttemptAt,
    Instant expiresAt,
    String errorCode) {

  public MessageRecord withStatus(String newStatus, int newAttempts, Instant next, String error) {
    return new MessageRecord(messageId, txnId, correlationId, channel, recipientMsisdn, templateCode,
        templateVersion, locale, priority, senderId, body, concatRef, segmentCount, newStatus, newAttempts,
        next, expiresAt, error);
  }
}
