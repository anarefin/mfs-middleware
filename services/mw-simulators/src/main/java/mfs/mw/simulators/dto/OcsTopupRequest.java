package mfs.mw.simulators.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import mfs.mw.simulators.util.Sensitive;
import mfs.mw.simulators.util.SensitiveType;

/** Prepaid top-up request of the generic OCS contract (amount: decimal string, major units). */
public record OcsTopupRequest(
    @NotBlank String transactionId,
    @Sensitive(SensitiveType.MSISDN) @Pattern(regexp = "^(\\+?880)?01[3-9][0-9]{8}$") String msisdn,
    @Pattern(regexp = "^[0-9]{1,9}(\\.[0-9]{1,2})?$") String amount) {}
