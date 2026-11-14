package mfs.mw.crypto.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import mfs.mw.crypto.util.MaskingPolicy;
import mfs.mw.crypto.util.Sensitive;
import mfs.mw.crypto.util.SensitiveType;

/**
 * {@code POST /internal/v1/pin/verify} (mw-auth: login, step-up, assertion).
 *
 * @param storedHmac the wallet's stored PIN HMAC (base64)
 * @param hmacKeyId the kid of the {@code PIN_HMAC} version it was made with ({@code pin-hmac.v1})
 */
public record VerifyPinRequest(
    @NotBlank @Pattern(regexp = Formats.WALLET_ID) @Sensitive(SensitiveType.MSISDN) String walletId,
    @Valid @NotNull EncryptedPin encryptedPin,
    @NotBlank @Pattern(regexp = Formats.HMAC) @Sensitive(SensitiveType.SECRET) String storedHmac,
    @NotBlank @Pattern(regexp = Formats.KEY_REF) String hmacKeyId) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
