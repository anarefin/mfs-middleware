package mfs.mw.seeder.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** mw-auth {@code GET /auth/v1/pin-key}: the PIN transport key (kid, algorithm, PEM SubjectPublicKeyInfo). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PinKeyView(String keyId, String algorithm, String publicKeyPem) {}
