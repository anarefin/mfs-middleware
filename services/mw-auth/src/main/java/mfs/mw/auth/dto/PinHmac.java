package mfs.mw.auth.dto;

import mfs.mw.auth.util.MaskingPolicy;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/** A PIN HMAC (mw-crypto {@code PIN_HMAC}, base64) and the key version that made it. */
public record PinHmac(@Sensitive(SensitiveType.SECRET) String hmac, String hmacKeyId) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
