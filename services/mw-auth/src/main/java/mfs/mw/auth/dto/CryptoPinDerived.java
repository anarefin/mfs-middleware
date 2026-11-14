package mfs.mw.auth.dto;

import mfs.mw.auth.util.MaskingPolicy;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/** mw-crypto {@code /pin/derive} answer: {@code weak} with the rule, or the HMAC and its key version. */
public record CryptoPinDerived(boolean weak, String rule, @Sensitive(SensitiveType.SECRET) String hmac, String hmacKeyId) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
