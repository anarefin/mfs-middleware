package mfs.mw.auth.dto;

import mfs.mw.auth.util.MaskingPolicy;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/** mw-crypto {@code /jws/sign} answer: the compact JWS, its kid ({@code <keyId>.v<n>}) and alg. */
public record CryptoJwsSigned(@Sensitive(SensitiveType.SECRET) String jws, String kid, String alg) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
