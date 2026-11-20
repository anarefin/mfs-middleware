package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/**
 * One saved biller of the signed-in wallet (ticket 107a). The account number is stored and
 * returned in the clear to its owner; {@link Sensitive} masks it in logs.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SavedBiller(
    String id,
    String billerCode,
    @Sensitive(SensitiveType.ACCOUNT) String accountNo,
    String nickname,
    Instant createdAt) {}
