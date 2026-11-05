package mfs.mw.simulators.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import mfs.mw.simulators.util.Sensitive;
import mfs.mw.simulators.util.SensitiveType;

/**
 * C2W debit request of the generic bank contract (ticket 23): debit the customer's linked bank
 * account {@code accountNumber} by {@code amount} (decimal string, major units) for wallet money.
 */
public record BankDebitRequest(
    @NotBlank String transactionId,
    @NotBlank @Pattern(regexp = "^[0-9]{6,20}$") @Sensitive(SensitiveType.ACCOUNT) String accountNumber,
    @NotBlank @Pattern(regexp = "^[0-9]{1,9}(\\.[0-9]{1,2})?$") String amount) {}
