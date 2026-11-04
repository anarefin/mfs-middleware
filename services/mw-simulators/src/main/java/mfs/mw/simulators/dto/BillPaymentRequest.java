package mfs.mw.simulators.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Bill payment request of the generic aggregator contract (amount: decimal string, major units). */
public record BillPaymentRequest(
    @NotBlank String transactionId,
    @NotBlank @Pattern(regexp = "^[A-Z0-9_]{1,32}$") String billerCode,
    @NotBlank @Pattern(regexp = "^[0-9A-Za-z-]{1,32}$") String billNumber,
    @NotBlank @Pattern(regexp = "^[0-9]{1,9}(\\.[0-9]{1,2})?$") String amount) {}
