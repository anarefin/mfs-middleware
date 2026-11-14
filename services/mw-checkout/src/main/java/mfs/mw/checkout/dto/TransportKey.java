package mfs.mw.checkout.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * mw-auth {@code GET /auth/v1/pin-key} (contract {@code api/mw-auth/auth.v1.openapi.yaml} {@code
 * PinKey}; ticket 72): the newest mw-crypto {@code PIN_TRANSPORT} public key (RSA-OAEP SHA-256), PEM
 * SubjectPublicKeyInfo. Not a secret.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransportKey(String keyId, String algorithm, String publicKeyPem) {}
