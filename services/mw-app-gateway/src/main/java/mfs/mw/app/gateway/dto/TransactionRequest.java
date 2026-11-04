package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import mfs.mw.app.gateway.util.Msisdn;
import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/**
 * Body of {@code POST /app/v1/transactions} (contract {@code
 * api/mw-app-gateway/app.v1.openapi.yaml}). The payer is never in the body: it is the wallet of
 * the token's subject (claim {@code msisdn}). TOPUP adds {@code partner} (operator and product)
 * and its credit party is the MSISDN to recharge.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransactionRequest(
    @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,31}") String txnType,
    @NotNull @Valid CounterParty creditParty,
    @NotNull @Valid Amount amount,
    @NotNull @Valid EncryptedPin encryptedPin,
    @Pattern(regexp = "bn|en") String locale,
    @Size(max = 20) Map<@Size(max = 64) String, @Size(max = 256) String> attributes,
    @Valid Partner partner) {

  /** A request without a partner (P2P and the other wallet flows). */
  public TransactionRequest(
      String txnType,
      CounterParty creditParty,
      Amount amount,
      EncryptedPin encryptedPin,
      String locale,
      Map<String, String> attributes) {
    this(txnType, creditParty, amount, encryptedPin, locale, attributes, null);
  }

  /**
   * The receiving side, addressed by MSISDN: a wallet ({@code WALLET}, the default) or, for
   * TOPUP, the mobile number to recharge ({@code MSISDN}).
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CounterParty(
      @Pattern(regexp = "WALLET|MSISDN") String type,
      @NotBlank
          @Pattern(regexp = Msisdn.PATTERN)
          @Sensitive(SensitiveType.MSISDN)
          String id) {}

  /** The operator ({@code partnerId}) and its product of a TOPUP. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Partner(
      @NotBlank @Pattern(regexp = "gp|robi|banglalink|teletalk") String partnerId,
      @NotBlank @Pattern(regexp = "PREPAID|POSTPAID") String productCode) {}
}
