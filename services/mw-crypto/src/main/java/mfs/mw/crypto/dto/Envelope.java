package mfs.mw.crypto.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import mfs.mw.crypto.util.MaskingPolicy;
import mfs.mw.crypto.util.Sensitive;
import mfs.mw.crypto.util.SensitiveType;

/**
 * An envelope (spec 18.8): the answer of {@code /envelope/encrypt} and the body of {@code
 * /envelope/decrypt}. Every byte field is standard base64.
 *
 * @param kekId the KEK version that wrapped the data key ({@code kek-column.v1})
 * @param wrappedKey the AES-256 data key wrapped by the KEK in the HSM (opaque)
 * @param iv the 12-byte GCM nonce of {@code ciphertext}
 * @param ciphertext AES-256-GCM output, the 16-byte tag at the end
 * @param aad the additional authenticated data, when there was any
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Envelope(
    @NotBlank @Pattern(regexp = Formats.KEY_REF) String kekId,
    @NotBlank @Size(max = 1024) @Pattern(regexp = Formats.BASE64) @Sensitive(SensitiveType.SECRET) String wrappedKey,
    @NotBlank @Pattern(regexp = "[A-Za-z0-9+/]{16}") String iv,
    @NotNull @Size(max = Formats.MAX_PLAINTEXT + 64) @Pattern(regexp = Formats.BASE64) @Sensitive(SensitiveType.SECRET)
        String ciphertext,
    @Size(max = Formats.MAX_DATA) @Pattern(regexp = Formats.BASE64) String aad) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
