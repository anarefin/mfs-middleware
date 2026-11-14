package mfs.mw.crypto.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import mfs.mw.crypto.util.MaskingPolicy;
import mfs.mw.crypto.util.Sensitive;
import mfs.mw.crypto.util.SensitiveType;

/**
 * What the weak-PIN rules of spec 18.7 need besides the PIN (mw-auth sends it; every field optional).
 *
 * @param msisdn no 5 consecutive digits of it may be the PIN
 * @param birthYear the PIN may not contain it (when the core supplies it)
 * @param lastHmac the current PIN's stored HMAC: the new PIN may not be the same
 * @param lastHmacKeyId the kid {@code lastHmac} was made with (required with it)
 */
public record PinPolicyInput(
    @Pattern(regexp = "\\+?[0-9]{8,15}") @Sensitive(SensitiveType.MSISDN) String msisdn,
    @Min(1900) @Max(2100) Integer birthYear,
    @Pattern(regexp = Formats.HMAC) @Sensitive(SensitiveType.SECRET) String lastHmac,
    @Pattern(regexp = Formats.KEY_REF) String lastHmacKeyId) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
