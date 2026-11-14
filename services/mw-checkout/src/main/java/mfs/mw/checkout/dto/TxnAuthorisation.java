package mfs.mw.checkout.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import mfs.mw.checkout.util.Sensitive;
import mfs.mw.checkout.util.SensitiveType;

/**
 * mw-auth's answer ({@code TxnAuthorisation}, ticket 72): the compact JWS for {@code
 * mw.txn.request.v1} {@code assertion}. Never logged (only its jti may appear, and only mw-auth logs it).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TxnAuthorisation(@Sensitive(SensitiveType.SECRET) String assertion, Instant expiresAt, boolean newDevice) {}
