package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/**
 * The signed-in wallet's own profile (ticket 107b, WAL-01). The owner sees the holder name; logs
 * keep the first initial ({@link Sensitive}).
 *
 * @param language the customer language, Bangla until a language switch is stored
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WalletProfile(
    @Sensitive(SensitiveType.NAME) String name,
    String type,
    String status,
    String kycLevel,
    String language) {}
