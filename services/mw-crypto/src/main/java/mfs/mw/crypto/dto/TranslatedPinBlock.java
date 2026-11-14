package mfs.mw.crypto.dto;

import mfs.mw.crypto.client.PinBlockFormat;
import mfs.mw.crypto.util.MaskingPolicy;
import mfs.mw.crypto.util.Sensitive;
import mfs.mw.crypto.util.SensitiveType;

/**
 * Answer of {@code POST /internal/v1/pinblock/translate}: the block (upper-case hex) under the ZPK
 * version {@code keyId} (kid), in {@code format}.
 */
public record TranslatedPinBlock(@Sensitive(SensitiveType.SECRET) String block, PinBlockFormat format, String keyId) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
