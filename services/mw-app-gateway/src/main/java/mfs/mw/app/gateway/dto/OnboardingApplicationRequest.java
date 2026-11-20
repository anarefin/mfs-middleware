package mfs.mw.app.gateway.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import mfs.mw.app.gateway.util.MaskingPolicy;
import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/**
 * Body of {@code POST /app/v1/onboarding/applications} (ticket 124b, FRS UC-01). {@code walletId} is the
 * customer's wallet: absent in the Customer app (the token's own), required in the DSO and Agent apps. The channel
 * and the agent id come from the token, never from the body.
 */
public record OnboardingApplicationRequest(
    @Sensitive(SensitiveType.MSISDN) @Pattern(regexp = "01[3-9]\\d{8}") String walletId,
    @Sensitive(SensitiveType.NID) @NotBlank @Pattern(regexp = "\\d{10}|\\d{13}|\\d{17}") String nid,
    @Sensitive(SensitiveType.SECRET) @NotNull LocalDate dob,
    @Sensitive(SensitiveType.NAME) @Size(max = 120) String nameEn,
    @Sensitive(SensitiveType.NAME) @Size(max = 120) String nameBn,
    @NotNull @Valid Consent consent) {

  /** The customer's consent (FRS CMP-04). */
  public record Consent(@NotBlank @Size(max = 64) String purpose, Instant grantedAt) {}

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
