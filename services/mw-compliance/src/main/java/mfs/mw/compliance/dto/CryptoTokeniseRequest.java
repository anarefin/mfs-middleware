package mfs.mw.compliance.dto;

import java.util.List;
import mfs.mw.compliance.util.MaskingPolicy;
import mfs.mw.compliance.util.Sensitive;
import mfs.mw.compliance.util.SensitiveType;

/** mw-crypto {@code POST /internal/v1/tokens} body ({@code crypto-internal.v1} TokeniseRequest). */
public record CryptoTokeniseRequest(String domain, @Sensitive(SensitiveType.SECRET) List<String> values) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
