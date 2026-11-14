package mfs.mw.auth.dto;

import java.time.Instant;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/**
 * {@code GET /internal/v1/devices/{deviceId}} (ticket 68): a bound device, its wallet and its P-256
 * public key (base64 X.509 SubjectPublicKeyInfo) for mw-app-gateway's signature check.
 */
public record BoundDevice(
    String deviceId, @Sensitive(SensitiveType.MSISDN) String walletId, String publicKey, Instant boundAt) {}
