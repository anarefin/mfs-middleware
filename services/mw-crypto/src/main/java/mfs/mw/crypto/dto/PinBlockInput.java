package mfs.mw.crypto.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import mfs.mw.crypto.client.PinBlockFormat;
import mfs.mw.crypto.util.MaskingPolicy;
import mfs.mw.crypto.util.Sensitive;
import mfs.mw.crypto.util.SensitiveType;

/**
 * An ISO 9564 PIN block under a payShield PIN key (ticket 64).
 *
 * @param keyId the TPK or ZPK (keyId or kid) the block is enciphered under
 * @param format ISO0, ISO1, ISO3 (triple DES keys, 16 hex digits) or ISO4 (AES keys, 32)
 * @param block the enciphered block, hex
 */
public record PinBlockInput(
    @NotBlank @Pattern(regexp = Formats.KEY_REF) String keyId,
    @NotNull PinBlockFormat format,
    @NotBlank @Pattern(regexp = PinBlockInput.HEX_BLOCK) @Sensitive(SensitiveType.SECRET) String block) {

  /** 16 or 32 hex digits. */
  public static final String HEX_BLOCK = "[0-9A-Fa-f]{16}|[0-9A-Fa-f]{32}";

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
