package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/**
 * mw-auth's {@code GET /internal/v1/devices/{deviceId}} answer (ticket 68; contract {@code
 * auth-internal.v1.openapi.yaml}): a bound device, its wallet and its P-256 public key (base64 X.509
 * SubjectPublicKeyInfo). Unknown fields are ignored (mw-auth owns the contract).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BoundDevice(
    String deviceId, @Sensitive(SensitiveType.MSISDN) String walletId, String publicKey, Instant boundAt) {}
