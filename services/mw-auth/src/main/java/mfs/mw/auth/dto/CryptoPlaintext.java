package mfs.mw.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import mfs.mw.auth.util.MaskingPolicy;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/** mw-crypto's answer to {@code /internal/v1/envelope/decrypt}: the base64 plaintext. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CryptoPlaintext(@Sensitive(SensitiveType.SECRET) String plaintext) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
