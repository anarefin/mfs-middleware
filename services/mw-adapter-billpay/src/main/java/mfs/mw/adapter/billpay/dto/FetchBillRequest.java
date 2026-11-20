package mfs.mw.adapter.billpay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import mfs.mw.adapter.billpay.util.Sensitive;
import mfs.mw.adapter.billpay.util.SensitiveType;

/**
 * Body of {@code POST /billpay/v1/billers/{code}/bills/fetch}. {@code billMonth} (YYYYMM, ticket
 * 113) is required for a biller whose catalogue entry says so.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FetchBillRequest(
    @NotBlank @Pattern(regexp = "[0-9A-Za-z-]{1,32}") @Sensitive(SensitiveType.ACCOUNT) String billNumber,
    @Pattern(regexp = "[0-9]{4}(0[1-9]|1[0-2])") String billMonth) {}
