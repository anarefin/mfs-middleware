package mfs.mw.notify.entity;

import java.time.Instant;
import mfs.mw.notify.util.Sensitive;
import mfs.mw.notify.util.SensitiveType;

/** A row of {@code notify.message} (spec 10, 16.2): one per requested channel of a request. */
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
    String errorCode,
    // EMAIL only (spec 10.2): address, rendered subject and HTML part; body is the text part.
    @Sensitive(SensitiveType.SECRET) String recipientEmail,
    String subject,
    @Sensitive(SensitiveType.SECRET) String htmlBody) {

  /** The key of a row: one per (messageId, channel). */
  public record Key(String messageId, String channel) {}

  public Key key() {
    return new Key(messageId, channel);
  }

  public MessageRecord withStatus(String newStatus, int newAttempts, Instant next, String error) {
    return new MessageRecord(messageId, txnId, correlationId, channel, recipientMsisdn, templateCode,
        templateVersion, locale, priority, senderId, body, concatRef, segmentCount, newStatus, newAttempts,
        next, expiresAt, error, recipientEmail, subject, htmlBody);
  }
}
