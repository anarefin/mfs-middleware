package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/**
 * PIN ciphertext exactly as the app sent it: RSA-OAEP (SHA-256, MGF1 SHA-256) under the mw-crypto
 * {@code PIN_TRANSPORT} key {@code keyId} ({@code GET /app/v1/pin-key}; ticket 72). This service
 * never decrypts, changes or logs it (18.3); it goes to mw-auth's txn-authorisations only, never
 * on Kafka.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EncryptedPin(
    @NotBlank @Size(max = 128) String keyId,
    @NotBlank @Size(max = 4096) @Sensitive(SensitiveType.SECRET) String ciphertext) {}
