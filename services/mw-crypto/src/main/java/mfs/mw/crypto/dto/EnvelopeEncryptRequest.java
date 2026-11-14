package mfs.mw.crypto.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import mfs.mw.crypto.util.MaskingPolicy;
import mfs.mw.crypto.util.Sensitive;
import mfs.mw.crypto.util.SensitiveType;

/**
 * {@code POST /internal/v1/envelope/encrypt}: AES-256-GCM under a fresh data key, wrapped by the
 * ACTIVE version of the KEK {@code kekId} or of the key of {@code purpose} ({@code KEK_<purpose>};
 * exactly one of the two).
 *
 * @param plaintext standard base64, at most 1 MiB
 * @param aad optional additional authenticated data (standard base64), bound to the ciphertext
 */
public record EnvelopeEncryptRequest(
    @Pattern(regexp = Formats.KEY_REF) String kekId,
    @Pattern(regexp = "KEK_[A-Z][A-Z0-9]{0,23}") String purpose,
    @NotNull @Size(max = Formats.MAX_PLAINTEXT) @Pattern(regexp = Formats.BASE64) @Sensitive(SensitiveType.SECRET)
        String plaintext,
    @Size(max = Formats.MAX_DATA) @Pattern(regexp = Formats.BASE64) String aad) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
