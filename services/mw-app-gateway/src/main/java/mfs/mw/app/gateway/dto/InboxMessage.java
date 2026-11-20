package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/**
 * One row of the signed-in wallet's notification inbox (ticket 107a). The MSISDN arrives already
 * masked from mw-notify; {@link Sensitive} keeps a log of this record masked too.
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

  /** One page of {@code GET /app/v1/notifications}. */
  public record Page(List<InboxMessage> items, int limit, int offset) {}
}
