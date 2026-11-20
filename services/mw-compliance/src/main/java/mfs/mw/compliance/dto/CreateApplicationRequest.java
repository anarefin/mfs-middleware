package mfs.mw.compliance.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import mfs.mw.compliance.util.MaskingPolicy;
import mfs.mw.compliance.util.Sensitive;
import mfs.mw.compliance.util.SensitiveType;

/**
 * {@code POST /internal/v1/kyc/applications} (compliance.v1 CreateApplication): the wallet, the channel (an
 * assisted channel names the agent or DSO from its token), the NID, date of birth and the name as the customer
 * gave them (English and/or Bangla, at least one), and the customer's consent (CMP-04).
 */
public record CreateApplicationRequest(
    @Sensitive(SensitiveType.MSISDN) @NotBlank @Pattern(regexp = "01[3-9]\\d{8}") String walletId,
    @NotNull Channel channel,
    @Size(max = 64) String agentId,
    @Sensitive(SensitiveType.NID) @NotBlank @Pattern(regexp = "\\d{10}|\\d{13}|\\d{17}") String nid,
    @Sensitive(SensitiveType.SECRET) @NotNull LocalDate dob,
    @Sensitive(SensitiveType.NAME) @Size(max = 120) String nameEn,
    @Sensitive(SensitiveType.NAME) @Size(max = 120) String nameBn,
    @NotNull @Valid ConsentRequest consent) {

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
