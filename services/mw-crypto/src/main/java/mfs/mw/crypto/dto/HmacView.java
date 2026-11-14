package mfs.mw.crypto.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import mfs.mw.crypto.util.MaskingPolicy;
import mfs.mw.crypto.util.Sensitive;
import mfs.mw.crypto.util.SensitiveType;

/**
 * Answer of {@code POST /internal/v1/hmac/sign} ({@code mac}, {@code kid}) and {@code /verify}
 * ({@code valid}, and the {@code kid} that matched).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HmacView(@Sensitive(SensitiveType.SECRET) String mac, String kid, Boolean valid) {

  public static HmacView signed(String mac, String kid) {
    return new HmacView(mac, kid, null);
  }

  public static HmacView verified(boolean valid, String kid) {
    return new HmacView(null, kid, valid);
  }

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
