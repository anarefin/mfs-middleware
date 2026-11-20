package mfs.mw.app.gateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/** {@code POST /app/v1/saved-billers}. The account number is the bill number at the biller. */
public record SaveBillerRequest(
    @NotBlank @Pattern(regexp = TransactionRequest.BILLER_CODE) String billerCode,
    @NotBlank @Pattern(regexp = TransactionRequest.BILL_NUMBER) @Sensitive(SensitiveType.ACCOUNT) String accountNo,
    @Size(max = 40) String nickname) {}
