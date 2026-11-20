package mfs.mw.auth.dto;

import java.time.Instant;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/**
 * {@code GET /internal/v1/sessions/{walletId}/{deviceId}} (ticket 68): the wallet's live session on
 * the device, just extended by its idle timeout, with the fingerprint (hex SHA-256 of the SPKI DER)
 * and binding time of the device's current key (ticket 69), so the gateway drops a stale cached key, and the
 * binding's epoch (ticket 110b), which the gateway copies into push token registrations for mw-notify.
 */
public record LiveSession(
    @Sensitive(SensitiveType.MSISDN) String walletId,
    String deviceId,
    long idleTimeoutSeconds,
    String deviceKeyFingerprint,
    Instant boundAt,
    long bindingEpoch) {}
