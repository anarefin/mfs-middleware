package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/**
 * mw-auth's answer to a transaction authorisation (ticket 72): the compact JWS that goes into
 * {@code mw.txn.request.v1} {@code assertion}. Never logged (only its jti is, by mw-auth).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TxnAuthorisation(
    @Sensitive(SensitiveType.SECRET) String assertion, Instant expiresAt, boolean newDevice) {}
