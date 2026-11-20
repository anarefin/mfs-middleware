package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/**
 * Body of {@code POST /app/v1/billers/{code}/fetch-bill}; {@code billMonth} (YYYYMM, ticket 113b) for
 * a biller whose catalogue entry needs it, passed to mw-adapter-billpay, which checks it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FetchBillRequest(
    @NotBlank @Pattern(regexp = TransactionRequest.BILL_NUMBER) @Sensitive(SensitiveType.ACCOUNT) String billNumber,
    @Pattern(regexp = "^[0-9]{4}(0[1-9]|1[0-2])$") String billMonth) {

  public FetchBillRequest(String billNumber) {
    this(billNumber, null);
  }
}
