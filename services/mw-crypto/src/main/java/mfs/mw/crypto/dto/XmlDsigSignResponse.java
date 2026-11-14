package mfs.mw.crypto.dto;

import mfs.mw.crypto.util.MaskingPolicy;
import mfs.mw.crypto.util.Sensitive;
import mfs.mw.crypto.util.SensitiveType;

/**
 * Answer of {@code POST /internal/v1/xmldsig/sign}: the signed document (base64 of UTF-8, the {@code
 * ds:Signature} the last child of the document element) and the key version that signed it.
 */
public record XmlDsigSignResponse(@Sensitive(SensitiveType.SECRET) String xml, String kid) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
