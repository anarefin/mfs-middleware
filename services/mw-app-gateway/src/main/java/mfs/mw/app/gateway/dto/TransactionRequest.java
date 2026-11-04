package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/**
 * Body of {@code POST /app/v1/transactions} (contract {@code
 * api/mw-app-gateway/app.v1.openapi.yaml}). The payer is never in the body: it is the wallet of
 * the token's subject (claim {@code msisdn}). TOPUP adds {@code partner} (operator and product)
 * and its credit party is the MSISDN to recharge; BILL_PAY adds {@code partner.billerCode} and its
 * credit party is the bill number ({@code BILLER_ACCOUNT}). Which shape a type takes is checked
 * by the service (per-flow rules); here only the field formats.
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

  /** A biller code (upper case, digits, underscore). */
  public static final String BILLER_CODE = "[A-Z0-9_]{1,32}";
  /** A bill or customer number at a biller. */
  public static final String BILL_NUMBER = "[0-9A-Za-z-]{1,32}";

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
   * The receiving side: a wallet ({@code WALLET}, the default) or, for TOPUP, the mobile number
   * to recharge ({@code MSISDN}), both addressed by MSISDN; for BILL_PAY the bill number
   * ({@code BILLER_ACCOUNT}).
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @CounterPartyId
  public record CounterParty(
      @Pattern(regexp = "WALLET|MSISDN|BILLER_ACCOUNT") String type,
      @NotBlank @Size(max = 32) @Sensitive(SensitiveType.MSISDN) String id) {}

  /**
   * TOPUP: the operator ({@code partnerId}) and its product. BILL_PAY: the biller only
   * ({@code billerCode}); the aggregator serving it is the middleware's choice.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Partner(
      @Pattern(regexp = "gp|robi|banglalink|teletalk") String partnerId,
      @Pattern(regexp = "PREPAID|POSTPAID") String productCode,
      @Pattern(regexp = BILLER_CODE) String billerCode) {

    public Partner(String partnerId, String productCode) {
      this(partnerId, productCode, null);
    }

    public static Partner biller(String billerCode) {
      return new Partner(null, null, billerCode);
    }

    /** Operator and product both present, no biller: a TOPUP partner. */
    public boolean isOperator() {
      return partnerId != null && productCode != null && billerCode == null;
    }

    /** Biller only: a BILL_PAY partner. */
    public boolean isBiller() {
      return billerCode != null && partnerId == null && productCode == null;
    }
  }
}
