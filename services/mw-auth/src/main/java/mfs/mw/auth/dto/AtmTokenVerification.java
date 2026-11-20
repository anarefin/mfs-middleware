package mfs.mw.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import mfs.mw.auth.util.MaskingPolicy;
import mfs.mw.auth.util.Msisdn;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/**
 * Body of {@code POST /internal/v1/atm-tokens/verifications} (ticket 120b2): what the ATM sent at
 * VerifyTokenAndDebit, the wallet's MSISDN, the amount (minor units), the currency and the token.
 */
public record AtmTokenVerification(
    @NotBlank @Pattern(regexp = Msisdn.PATTERN) @Sensitive(SensitiveType.MSISDN) String msisdn,
    @Positive long amount,
    @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
    @NotBlank @Pattern(regexp = "[0-9]{6}") @Sensitive(SensitiveType.SECRET) String token) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
