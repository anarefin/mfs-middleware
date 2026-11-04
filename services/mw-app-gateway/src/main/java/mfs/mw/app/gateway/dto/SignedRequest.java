package mfs.mw.app.gateway.dto;

import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/**
 * A device-signed app request (spec 9.1, 18.6): what the signature covers and the signature
 * itself, taken from the headers {@code X-Device-Id}, {@code X-Timestamp} (epoch milliseconds),
 * {@code X-Nonce}, {@code X-Signature} (base64 ECDSA P-256, DER or P1363) and the raw body.
 *
 * @param bodySha256 lower-case hex SHA-256 of the exact body bytes (of nothing when no body)
 */
public record SignedRequest(
    String deviceId,
    String method,
    String path,
    String bodySha256,
    String timestamp,
    String nonce,
    @Sensitive(SensitiveType.SECRET) String signature) {}
