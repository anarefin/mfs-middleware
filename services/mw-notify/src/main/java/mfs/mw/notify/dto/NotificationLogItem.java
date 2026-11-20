package mfs.mw.notify.dto;

import java.time.Instant;
import java.util.List;
import mfs.mw.notify.util.MaskingPolicy;
import mfs.mw.notify.util.SensitiveType;

/**
 * One row of {@code GET /internal/v1/notifications} (ticket 111, NOT-08). The MSISDN is masked.
 * An OTP message's body is always {@link #OTP_BODY}, whatever was stored.
 */
public record NotificationLogItem(
    String messageId,
    String txnId,
    String channel,
    String msisdn,
    String priority,
    String status,
    String templateCode,
    String body,
    Instant createdAt) {

  /** The only body an OTP row is ever shown as. */
  public static final String OTP_BODY = "****";

  /** A stored row as the log shows it. */
  public static NotificationLogItem of(String messageId, String txnId, String channel, String msisdn, String priority,
      String status, String templateCode, String body, Instant createdAt) {
    String shown = "OTP".equals(priority) ? OTP_BODY : body;
    return new NotificationLogItem(messageId, txnId, channel, MaskingPolicy.mask(SensitiveType.MSISDN, msisdn),
        priority, status, templateCode, shown, createdAt);
  }

  /** One page. */
  public record Page(List<NotificationLogItem> items, int limit, int offset) {}
}
