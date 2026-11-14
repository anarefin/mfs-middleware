package mfs.mw.crypto.dto;

import mfs.mw.crypto.util.MaskingPolicy;
import mfs.mw.crypto.util.Sensitive;
import mfs.mw.crypto.util.SensitiveType;

/** Answer of {@code POST /internal/v1/envelope/decrypt}: the plaintext, standard base64. */
public record PlaintextView(@Sensitive(SensitiveType.SECRET) String plaintext) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
