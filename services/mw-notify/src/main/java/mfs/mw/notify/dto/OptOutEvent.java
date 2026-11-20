package mfs.mw.notify.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import mfs.mw.notify.util.Sensitive;
import mfs.mw.notify.util.SensitiveType;

/**
 * {@code mw.notify.opt-out.v1} (ticket 111, NOT-09): the app opted the wallet out of promotional
 * messages. The wallet id is an MSISDN and is masked in logs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OptOutEvent(
    int schemaVersion,
    @Sensitive(SensitiveType.MSISDN) String walletId,
    String channel,
    Instant optedOutAt,
    String correlationId) {}
