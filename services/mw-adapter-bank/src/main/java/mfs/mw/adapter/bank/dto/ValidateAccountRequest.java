package mfs.mw.adapter.bank.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import mfs.mw.adapter.bank.util.Sensitive;
import mfs.mw.adapter.bank.util.SensitiveType;

/** Body of {@code POST /bank/v1/accounts/validate} (ticket 117b). The profile is {@code bank-<bankCode>}. */
public record ValidateAccountRequest(
    @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,40}") String bankCode,
    @NotBlank @Pattern(regexp = "[0-9]{6,20}") @Sensitive(SensitiveType.ACCOUNT) String accountNumber) {}
