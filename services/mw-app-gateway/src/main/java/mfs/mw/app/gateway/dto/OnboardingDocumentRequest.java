package mfs.mw.app.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import mfs.mw.app.gateway.util.MaskingPolicy;
import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/** Body of {@code POST /app/v1/onboarding/applications/{id}/documents} (ticket 124b): one image, base64. */
public record OnboardingDocumentRequest(
    @NotBlank @Pattern(regexp = "NID_FRONT|NID_BACK|SELFIE") String type,
    @NotBlank String contentType,
    @Sensitive(SensitiveType.SECRET) @NotBlank String content) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
