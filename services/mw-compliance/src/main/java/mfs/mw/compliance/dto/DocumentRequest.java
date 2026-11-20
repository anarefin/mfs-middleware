package mfs.mw.compliance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import mfs.mw.compliance.util.MaskingPolicy;
import mfs.mw.compliance.util.Sensitive;
import mfs.mw.compliance.util.SensitiveType;

/**
 * {@code POST /internal/v1/kyc/applications/{id}/documents}: one image, base64. The content type must be one of
 * {@code mw.compliance.kyc.content-types} and match the image's magic bytes; at most {@code max-document-bytes}.
 */
public record DocumentRequest(
    @NotNull DocumentType type,
    @NotBlank String contentType,
    @Sensitive(SensitiveType.SECRET) @NotBlank String content) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
