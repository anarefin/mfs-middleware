package mfs.mw.ussd.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * mw-auth {@code GET /auth/v1/pin-key} (contract {@code api/mw-auth/auth.v1.openapi.yaml}): the
 * newest mw-crypto {@code PIN_TRANSPORT} public key, its kid and algorithm, PEM SubjectPublicKeyInfo.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PinKey(String keyId, String algorithm, String publicKeyPem) {}
