package mfs.mw.core.mock.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import mfs.mw.core.mock.util.MaskingPolicy;
import mfs.mw.core.mock.util.Sensitive;
import mfs.mw.core.mock.util.SensitiveType;

/**
 * Payload of {@code VerifyCustomerFactors} (the internal core command; contract {@code core.command.v1} retired in ticket 95b, ticket 70): mw-auth's
 * self-service PIN reset; the KYC factors as TOKENISE_NID hashes, never in clear. No PIN.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FactorsPayload(Party subject, String resetId, Factors factors) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Factors(
      @Sensitive(SensitiveType.SECRET) String nidLast4Token,
      @Sensitive(SensitiveType.SECRET) String dateOfBirthToken,
      String tokenKid) {}

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
