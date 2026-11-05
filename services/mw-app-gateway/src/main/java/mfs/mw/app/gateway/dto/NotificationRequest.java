package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/**
 * The request this service publishes on {@code mw.notify.request.v1} (spec 10.1; contract {@code
 * events/mw-notify/mw.notify.request.v1}, owned by mw-notify): the device binding OTP and the "new
 * device bound" SMS. Only the fields this service sets. The params can hold the OTP, so the whole
 * map is {@code SECRET} for logging.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationRequest(
    int schemaVersion,
    String messageId,
    String correlationId,
    Recipient recipient,
    String templateCode,
    String locale,
    @Sensitive(SensitiveType.SECRET) Map<String, String> params,
    List<String> channels,
    String priority,
    Instant expiresAt) {

  /** The SMS recipient. */
  public record Recipient(@Sensitive(SensitiveType.MSISDN) String msisdn) {}
}
