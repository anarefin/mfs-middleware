package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/**
 * {@code mw.notify.push-token.v1} as this service publishes it (ticket 110; contract {@code
 * events/mw-notify/mw.notify.push-token.v1}, owned by mw-notify). The token is not personal data but is
 * never logged in clear. {@code bindingEpoch} is mw-auth's epoch of the device's current binding (ticket 110b),
 * by which mw-notify refuses a registration of an older binding, whatever the clocks.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PushTokenMessage(
    int schemaVersion,
    @Sensitive(SensitiveType.MSISDN) String walletId,
    String deviceId,
    @Sensitive(SensitiveType.SECRET) String token,
    String platform,
    Instant registeredAt,
    Long bindingEpoch,
    String correlationId) {}
