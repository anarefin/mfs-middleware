package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/**
 * {@code mw.notify.opt-out.v1} as this service publishes it (ticket 111, NOT-09; contract owned by
 * mw-notify). The wallet id is the token's MSISDN. Promotional messages only: OTP and transactional
 * messages are never opted out.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OptOutMessage(
    int schemaVersion,
    @Sensitive(SensitiveType.MSISDN) String walletId,
    String channel,
    Instant optedOutAt,
    String correlationId) {}
