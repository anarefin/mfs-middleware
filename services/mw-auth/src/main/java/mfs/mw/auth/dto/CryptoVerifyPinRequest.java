package mfs.mw.auth.dto;

import mfs.mw.auth.util.MaskingPolicy;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/** mw-crypto {@code POST /internal/v1/pin/verify} body ({@code crypto-internal.v1} VerifyPinRequest). */
public record CryptoVerifyPinRequest(
    String walletId,
    EncryptedPin encryptedPin,
    @Sensitive(SensitiveType.SECRET) String storedHmac,
    String hmacKeyId) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
