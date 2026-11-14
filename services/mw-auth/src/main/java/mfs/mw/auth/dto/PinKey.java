package mfs.mw.auth.dto;

/** {@code GET /auth/v1/pin-key}: the PIN transport public key (kid, algorithm, PEM SubjectPublicKeyInfo). */
public record PinKey(String keyId, String algorithm, String publicKeyPem) {}
