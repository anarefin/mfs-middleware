package mfs.mw.crypto.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import mfs.mw.crypto.util.MaskingPolicy;
import mfs.mw.crypto.util.Sensitive;
import mfs.mw.crypto.util.SensitiveType;

/**
 * A PIN transport block (spec 18.7): the PIN's ASCII digits encrypted RSA-OAEP (SHA-256, MGF1-SHA-256)
 * under the {@code PIN_TRANSPORT} key {@code keyId} (or one version of it, {@code pin-transport.v2}).
 *
 * @param ciphertext standard base64 of the RSA block
 */
public record EncryptedPin(
    @NotBlank @Pattern(regexp = Formats.KEY_REF) String keyId,
    @NotBlank @Size(max = 4096) @Pattern(regexp = Formats.BASE64) @Sensitive(SensitiveType.SECRET)
        String ciphertext) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
