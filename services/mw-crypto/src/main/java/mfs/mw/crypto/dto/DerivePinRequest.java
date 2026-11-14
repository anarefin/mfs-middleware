package mfs.mw.crypto.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import mfs.mw.crypto.util.MaskingPolicy;
import mfs.mw.crypto.util.Sensitive;
import mfs.mw.crypto.util.SensitiveType;

/**
 * {@code POST /internal/v1/pin/derive} (mw-auth: PIN set, change and reset).
 *
 * @param hmacKeyId optional keyId of the {@code PIN_HMAC} key (its ACTIVE version); default the
 *     key of the purpose {@code PIN_HMAC} ({@code pin-hmac})
 */
public record DerivePinRequest(
    @NotBlank @Pattern(regexp = Formats.WALLET_ID) @Sensitive(SensitiveType.MSISDN) String walletId,
    @Valid @NotNull EncryptedPin encryptedPin,
    @Valid PinPolicyInput policyInput,
    @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,62}") String hmacKeyId) {

  public DerivePinRequest(String walletId, EncryptedPin encryptedPin, PinPolicyInput policyInput) {
    this(walletId, encryptedPin, policyInput, null);
  }

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
