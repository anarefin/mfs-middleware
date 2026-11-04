package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/** Body of {@code POST /app/v1/billers/{code}/fetch-bill}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FetchBillRequest(
    @NotBlank @Pattern(regexp = TransactionRequest.BILL_NUMBER) @Sensitive(SensitiveType.ACCOUNT) String billNumber) {}
