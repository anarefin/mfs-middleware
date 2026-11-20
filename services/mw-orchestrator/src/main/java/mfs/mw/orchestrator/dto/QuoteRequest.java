package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import mfs.mw.orchestrator.util.Sensitive;
import mfs.mw.orchestrator.util.SensitiveType;

/**
 * Body of {@code POST /internal/v1/quotes} (contract {@code txn-query.v1} 1.5.0, ticket 105): what a
 * transaction of the signed-in wallet would cost, asked by mw-app-gateway before the PIN. A top-up
 * names {@code productCode} PREPAID or POSTPAID (ticket 112b).
 *
 * @param walletId the payer's wallet (canonical MSISDN; from the caller's token, never the app body)
 * @param walletType the payer's wallet type (the core's {@code customerType} of the charges)
 * @param counterparty the payee; its account is read only for a {@code WALLET}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuoteRequest(
    @NotBlank @Pattern(regexp = "01[3-9][0-9]{8}") @Sensitive(SensitiveType.MSISDN) String walletId,
    @NotBlank @Pattern(regexp = "CUSTOMER|AGENT|MERCHANT|DISTRIBUTOR|DSO|PARTNER|ORGANISATION") String walletType,
    @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,31}") String txnType,
    @Pattern(regexp = "[A-Z][A-Z0-9_]{0,31}") String channel,
    @NotNull @Valid Money amount,
    @Valid Counterparty counterparty,
    @Pattern(regexp = "PREPAID|POSTPAID") String productCode) {

  /** A quote that is not a top-up (no product). */
  @JsonIgnore
  public QuoteRequest(
      String walletId, String walletType, String txnType, String channel, Money amount, Counterparty counterparty) {
    this(walletId, walletType, txnType, channel, amount, counterparty, null);
  }

  /** An amount in the request (BDT only). */
  public record Money(@PositiveOrZero long valueMinor, @NotBlank @Pattern(regexp = "BDT") String currency) {
    public Amount toAmount() {
      return new Amount(valueMinor, currency);
    }
  }

  /** The payee as the channel named it. */
  public record Counterparty(
      @NotBlank @Pattern(regexp = "WALLET|MSISDN|BILLER_ACCOUNT|BANK_ACCOUNT") String type,
      @NotBlank @Size(max = 64) @Sensitive(SensitiveType.PARTY_ID) String id) {}
}
