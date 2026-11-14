package mfs.mw.simulators.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import mfs.mw.simulators.service.PayShieldKeys.KeyType;
import mfs.mw.simulators.util.MaskingPolicy;
import mfs.mw.simulators.util.PinBlocks.Format;
import mfs.mw.simulators.util.Sensitive;
import mfs.mw.simulators.util.SensitiveType;

/**
 * {@code POST /sim/payshield/pinblock} (ticket 64, test helper): what an ATM or switch sharing the key
 * would send, a PIN block of {@code pin} under the key {@code key} (a blob under the LMK, from
 * {@code A0}).
 *
 * @param keyType TPK or ZPK
 * @param format ISO0, ISO1, ISO3 or ISO4 (ISO4 needs an S, AES, key)
 * @param pan the account number the block is bound to (12 to 19 digits)
 */
public record PayShieldPinBlockRequest(
    @NotNull KeyType keyType,
    @NotBlank @Pattern(regexp = "[US][0-9A-F]{32}") String key,
    @NotNull Format format,
    @NotBlank @Pattern(regexp = "[0-9]{4,12}") @Sensitive(SensitiveType.SECRET) String pin,
    @NotBlank @Pattern(regexp = "[0-9]{12,19}") @Sensitive(SensitiveType.ACCOUNT) String pan) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
