package mfs.mw.crypto.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import mfs.mw.crypto.client.PinBlockFormat;
import mfs.mw.crypto.util.MaskingPolicy;
import mfs.mw.crypto.util.Sensitive;
import mfs.mw.crypto.util.SensitiveType;

/**
 * {@code POST /internal/v1/pinblock/translate} (ticket 64): a PIN block under a TPK (payShield {@code
 * CA}) or ZPK ({@code CC}) re-enciphered under a ZPK, the clear PIN only inside the HSM.
 *
 * @param destination the ZPK and the format of the new block
 * @param account the account number the blocks are bound to (ISO 9564 "PAN"; for a wallet its account
 *     number: a card PAN never enters the middleware), 12 to 19 digits with its check digit
 */
public record TranslatePinBlockRequest(
    @NotNull @Valid PinBlockInput source,
    @NotNull @Valid Target destination,
    @NotBlank @Pattern(regexp = TranslatePinBlockRequest.ACCOUNT) @Sensitive(SensitiveType.ACCOUNT) String account) {

  /** 12 to 19 digits. */
  public static final String ACCOUNT = "[0-9]{12,19}";

  /** The key and format of the translated block. */
  public record Target(@NotBlank @Pattern(regexp = Formats.KEY_REF) String keyId, @NotNull PinBlockFormat format) {}

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
