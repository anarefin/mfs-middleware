package mfs.mw.compliance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * The customer's consent (FRS CMP-04): what the data is used for and when it was given (now when absent).
 *
 * @param purpose e.g. {@code EKYC_ONBOARDING}
 * @param grantedAt when the customer agreed on the device
 */
public record ConsentRequest(@NotBlank @Size(max = 64) String purpose, Instant grantedAt) {}
