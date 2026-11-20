package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/**
 * mw-auth's {@code GET /internal/v1/sessions/{walletId}/{deviceId}} answer (contract {@code
 * auth-internal.v1.openapi.yaml}): the live session, extended, with the fingerprint (hex SHA-256 of
 * the SubjectPublicKeyInfo DER) and binding time of the device's current key (ticket 69), and the binding's
 * epoch (ticket 110b; null only from an mw-auth before it), copied into push token registrations. Unknown
 * fields are ignored (mw-auth owns the contract).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LiveSession(
    @Sensitive(SensitiveType.MSISDN) String walletId,
    String deviceId,
    long idleTimeoutSeconds,
    String deviceKeyFingerprint,
    Instant boundAt,
    Long bindingEpoch) {}
