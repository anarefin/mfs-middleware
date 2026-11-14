package mfs.mw.crypto.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import mfs.mw.crypto.util.MaskingPolicy;
import mfs.mw.crypto.util.Sensitive;
import mfs.mw.crypto.util.SensitiveType;

/**
 * {@code POST /internal/v1/xmldsig/verify} (ticket 65): exactly one of {@code keyId} (an {@code
 * XMLDSIG} key here: its ACTIVE and VERIFY_ONLY versions, or the version a kid names) and {@code
 * certificatePem} (the partner's X.509 certificate or public key). The document's own {@code
 * KeyInfo} is never used.
 */
public record XmlDsigVerifyRequest(
    @NotBlank @Size(max = Formats.MAX_PLAINTEXT) @Pattern(regexp = Formats.BASE64) @Sensitive(SensitiveType.SECRET)
        String xml,
    @Pattern(regexp = Formats.KEY_REF) String keyId,
    @Size(max = Formats.MAX_PEM) String certificatePem) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
