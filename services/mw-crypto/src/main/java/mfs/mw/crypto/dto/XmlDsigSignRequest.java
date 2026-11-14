package mfs.mw.crypto.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import mfs.mw.crypto.util.MaskingPolicy;
import mfs.mw.crypto.util.Sensitive;
import mfs.mw.crypto.util.SensitiveType;

/**
 * {@code POST /internal/v1/xmldsig/sign} (ticket 65, spec 18.8): an enveloped XML-DSig signature
 * (RSA-SHA256, exclusive C14N, the whole document) by the ACTIVE version of an {@code XMLDSIG} key.
 *
 * @param keyId optional; default the key of purpose {@code XMLDSIG}
 * @param xml the UTF-8 document, standard base64, at most 1 MiB; no DOCTYPE
 */
public record XmlDsigSignRequest(
    @Pattern(regexp = Formats.KEY_REF) String keyId,
    @NotBlank @Size(max = Formats.MAX_PLAINTEXT) @Pattern(regexp = Formats.BASE64) @Sensitive(SensitiveType.SECRET)
        String xml) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
