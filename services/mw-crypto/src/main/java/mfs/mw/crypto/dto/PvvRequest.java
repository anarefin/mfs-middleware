package mfs.mw.crypto.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import mfs.mw.crypto.util.MaskingPolicy;
import mfs.mw.crypto.util.Sensitive;
import mfs.mw.crypto.util.SensitiveType;

/**
 * {@code POST /internal/v1/pvv/generate} (no {@code pvv}) and {@code /pvv/verify} (with it), ticket
 * 64: the Visa PIN verification value of the PIN in {@code pinBlock} under the PVK pair {@code pvkId}.
 *
 * @param pvki the PVK index (0 to 9) that is part of the PVV input
 * @param pvv the stored PVV to check (verify only), 4 digits
 * @param account the account number the PIN block is bound to, 12 to 19 digits with its check digit
 */
public record PvvRequest(
    @NotBlank @Pattern(regexp = Formats.KEY_REF) String pvkId,
    @NotNull @Min(0) @Max(9) Integer pvki,
    @NotNull @Valid PinBlockInput pinBlock,
    @NotBlank @Pattern(regexp = TranslatePinBlockRequest.ACCOUNT) @Sensitive(SensitiveType.ACCOUNT) String account,
    @Pattern(regexp = "[0-9]{4}") @Sensitive(SensitiveType.SECRET) String pvv) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
