package mfs.mw.adapter.bank.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import mfs.mw.adapter.bank.util.Sensitive;
import mfs.mw.adapter.bank.util.SensitiveType;

/**
 * Body of {@code POST /bank/v1/accounts/validate} (ticket 117b). The profile is {@code bank-<bankCode>}.
 * {@code matchName} (optional, M9 review owner binding): the wallet's KYC name; the answer then says
 * whether the bank's holder name is the same person ({@code nameMatch}).
 */
public record ValidateAccountRequest(
    @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,40}") String bankCode,
    @NotBlank @Pattern(regexp = "[0-9]{6,20}") @Sensitive(SensitiveType.ACCOUNT) String accountNumber,
    @Size(max = 120) @Sensitive(SensitiveType.NAME) String matchName) {}
