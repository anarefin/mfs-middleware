package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/**
 * PIN ciphertext exactly as the app sent it (RSA-OAEP, SHA-256, with the core's public key;
 * contract {@code EncryptedPin}). This service never decrypts, changes or logs it (18.3).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EncryptedPin(
    @NotBlank @Size(max = 128) String keyId,
    @NotBlank @Size(max = 4096) @Sensitive(SensitiveType.SECRET) String ciphertext) {}
