package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/**
 * One favourite counterparty of the signed-in wallet (ticket 107a). The MSISDN is stored and
 * returned in the clear to its owner; {@link Sensitive} masks it in logs.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Favourite(
    String id,
    @Sensitive(SensitiveType.MSISDN) String msisdn,
    String label,
    Instant createdAt) {}
