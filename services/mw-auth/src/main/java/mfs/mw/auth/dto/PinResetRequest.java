package mfs.mw.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import mfs.mw.auth.util.MaskingPolicy;
import mfs.mw.auth.util.Msisdn;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/**
 * Body of {@code POST /auth/v1/pin-resets} (ticket 70): the wallet, the {@code RESET} OTP and, for a
 * self-service reset, the KYC factors (both or neither). The factors are personal data: never logged,
 * hashed by mw-crypto before they leave.
 */
public record PinResetRequest(
    @NotBlank @Pattern(regexp = Msisdn.PATTERN) @Sensitive(SensitiveType.MSISDN) String msisdn,
    @NotBlank @Pattern(regexp = "^[0-9]{6}$") @Sensitive(SensitiveType.SECRET) String otp,
    @Pattern(regexp = "^[0-9]{4}$") @Sensitive(SensitiveType.SECRET) String nidLast4,
    @Pattern(regexp = "^[0-9]{4}-[0-9]{2}-[0-9]{2}$") @Sensitive(SensitiveType.SECRET) String dateOfBirth) {

  /** Self-service: the factors are given. */
  public boolean selfService() {
    return nidLast4 != null || dateOfBirth != null;
  }

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
