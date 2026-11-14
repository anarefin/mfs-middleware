package mfs.mw.crypto.dto;

import mfs.mw.crypto.util.MaskingPolicy;
import mfs.mw.crypto.util.Sensitive;
import mfs.mw.crypto.util.SensitiveType;

/**
 * A PIN HMAC to store: HMAC-SHA256 under {@code PIN_HMAC} over {@code walletId ‖ 0x00 ‖ PIN}.
 *
 * @param hmac base64
 * @param hmacKeyId the kid of the {@code PIN_HMAC} version ({@code pin-hmac.v2})
 */
public record PinHmac(@Sensitive(SensitiveType.SECRET) String hmac, String hmacKeyId) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
