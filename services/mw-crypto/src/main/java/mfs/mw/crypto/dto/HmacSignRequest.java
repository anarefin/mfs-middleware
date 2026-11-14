package mfs.mw.crypto.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import mfs.mw.crypto.util.MaskingPolicy;
import mfs.mw.crypto.util.Sensitive;
import mfs.mw.crypto.util.SensitiveType;

/**
 * {@code POST /internal/v1/hmac/sign}: HMAC-SHA256 under the ACTIVE version of an {@code HMAC_<use>}
 * key (partner signatures, webhooks).
 *
 * @param data the bytes to sign, standard base64, at most 64 KiB
 */
public record HmacSignRequest(
    @NotBlank @Pattern(regexp = Formats.KEY_REF) String keyId,
    @NotNull @Size(max = Formats.MAX_DATA) @Pattern(regexp = Formats.BASE64) @Sensitive(SensitiveType.SECRET)
        String data) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
