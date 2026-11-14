package mfs.mw.ussd.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import mfs.mw.ussd.util.Sensitive;
import mfs.mw.ussd.util.SensitiveType;

/**
 * The PIN a customer typed, encrypted on receipt under the mw-crypto {@code PIN_TRANSPORT} key
 * (RSA-OAEP, SHA-256, MGF1-SHA-256; ticket 72) and sent to mw-auth only, once. Never stored, logged
 * or published.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EncryptedPin(
    @NotBlank @Size(max = 128) String keyId,
    @NotBlank @Size(max = 4096) @Sensitive(SensitiveType.SECRET) String ciphertext) {}
