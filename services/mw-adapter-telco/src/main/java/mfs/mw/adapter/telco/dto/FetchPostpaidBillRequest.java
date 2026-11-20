package mfs.mw.adapter.telco.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import mfs.mw.adapter.telco.util.Sensitive;
import mfs.mw.adapter.telco.util.SensitiveType;

/** Body of {@code POST /telco/v1/bills/fetch}. The number is the subscriber, not a wallet. */
public record FetchPostpaidBillRequest(
    @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,63}") String partnerId,
    @NotBlank @Pattern(regexp = "01[3-9][0-9]{8}") @Sensitive(SensitiveType.MSISDN) String msisdn) {}
