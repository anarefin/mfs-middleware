package mfs.mw.checkout.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import mfs.mw.checkout.util.Sensitive;
import mfs.mw.checkout.util.SensitiveType;

/**
 * PIN ciphertext exactly as the page sent it (RSA-OAEP, SHA-256, under the mw-crypto PIN transport
 * key; ticket 72). This service never decrypts, changes or logs it: it goes to mw-auth's
 * txn-authorisations only, never onto Kafka (18.3).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EncryptedPin(
    @NotBlank @Size(max = 128) String keyId,
    @NotBlank @Size(max = 4096) @Sensitive(SensitiveType.SECRET) String ciphertext) {}
