package mfs.mw.notify.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import mfs.mw.notify.util.MaskingPolicy;
import mfs.mw.notify.util.Sensitive;
import mfs.mw.notify.util.SensitiveType;

/**
 * One row of {@code GET /internal/v1/inbox} (ticket 107a). OTP rows are not built at all. The
 * MSISDN is masked; the body is the stored text (params already masked at send).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InboxMessage(
    String id,
    String channel,
    String txnId,
    @Sensitive(SensitiveType.MSISDN) String msisdn,
    String priority,
    String body,
    boolean read,
    Instant createdAt) {

  /** A stored row as the inbox shows it. */
  public static InboxMessage of(String id, String channel, String txnId, String msisdn, String priority, String body,
      Instant readAt, Instant createdAt) {
    return new InboxMessage(id, channel, txnId, MaskingPolicy.mask(SensitiveType.MSISDN, msisdn), priority, body,
        readAt != null, createdAt);
  }

  /** One page. */
  public record Page(List<InboxMessage> items, int limit, int offset) {}
}
