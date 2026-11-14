package mfs.mw.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import mfs.mw.auth.util.MaskingPolicy;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/**
 * A PIN transport block (spec 18.7, 18.8): the PIN's digits encrypted RSA-OAEP (SHA-256, MGF1-SHA-256)
 * under the mw-crypto {@code PIN_TRANSPORT} key {@code keyId} (a kid {@code <keyId>.v<n>}). mw-auth only
 * passes it on to mw-crypto; it never sees the PIN. Never logged.
 */
public record EncryptedPin(
    @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{0,62}(\\.v[1-9][0-9]{0,5})?$") String keyId,
    @Sensitive(SensitiveType.SECRET) @NotBlank @Size(max = 4096) @Pattern(regexp = "^[A-Za-z0-9+/]*={0,2}$")
        String ciphertext) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
