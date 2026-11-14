package mfs.mw.crypto.dto;

import mfs.mw.crypto.util.MaskingPolicy;
import mfs.mw.crypto.util.Sensitive;
import mfs.mw.crypto.util.SensitiveType;

/**
 * Answer of {@code POST /internal/v1/jws/sign}.
 *
 * @param jws the compact serialisation
 * @param kid the key version that signed it ({@code <keyId>.v<n>}, also the header {@code kid})
 */
public record JwsSignResponse(@Sensitive(SensitiveType.SECRET) String jws, String kid, String alg) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
