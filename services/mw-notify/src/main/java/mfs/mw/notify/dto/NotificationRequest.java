package mfs.mw.notify.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import mfs.mw.notify.util.Sensitive;
import mfs.mw.notify.util.SensitiveType;

/**
 * {@code mw.notify.request.v1} (contract {@code events/mw-notify/mw.notify.request.v1}, owned by
 * this service; spec 10.1). Any service produces it; the rule engine builds the same type from
 * {@code mw.txn.status.v1}. {@code messageId} is the idempotency key.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationRequest(
    int schemaVersion,
    String messageId,
    String txnId,
    String correlationId,
    Recipient recipient,
    String templateCode,
    String locale,
    // Params may hold personal data before masking; never logged in clear.
    @Sensitive(SensitiveType.SECRET) Map<String, String> params,
    List<Channel> channels,
    Priority priority,
    Instant sendAt,
    Instant expiresAt) {

  /** Who gets the message; at least one address. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Recipient(
      @Sensitive(SensitiveType.MSISDN) String msisdn,
      @Sensitive(SensitiveType.SECRET) String email,
      @Sensitive(SensitiveType.SECRET) String pushToken,
      String subjectId) {}

  public NotificationRequest {
    params = params == null ? Map.of() : Map.copyOf(params);
    channels = channels == null ? List.of() : List.copyOf(channels);
  }
}
