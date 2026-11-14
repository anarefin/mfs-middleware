package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The PIN transport public key (ticket 72): mw-auth {@code GET /auth/v1/pin-key}, the newest
 * mw-crypto {@code PIN_TRANSPORT} key, served to the apps as {@code GET /app/v1/pin-key}: its kid
 * (the {@code encryptedPin.keyId} to send), the algorithm and the PEM SubjectPublicKeyInfo.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PinKey(String keyId, String algorithm, String publicKeyPem) {}
