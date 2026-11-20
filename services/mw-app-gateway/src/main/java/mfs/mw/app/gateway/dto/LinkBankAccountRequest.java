package mfs.mw.app.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import mfs.mw.app.gateway.util.MaskingPolicy;
import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/** Body of {@code POST /app/v1/bank-accounts} (ticket 117b). */
public record LinkBankAccountRequest(
    @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,40}") String bankCode,
    @NotBlank @Pattern(regexp = "[0-9]{6,20}") @Sensitive(SensitiveType.ACCOUNT) String accountNumber) {

  /** Never the full number, even in a debugger or an exception message. */
  @Override
  public String toString() {
    return "LinkBankAccountRequest[bankCode=" + bankCode + ", accountNumber="
        + MaskingPolicy.mask(SensitiveType.ACCOUNT, accountNumber) + "]";
  }
}
