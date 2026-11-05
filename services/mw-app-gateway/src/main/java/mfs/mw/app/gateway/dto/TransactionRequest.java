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
    @Valid CounterParty creditParty,
    @NotNull @Valid Amount amount,
    @NotNull @Valid EncryptedPin encryptedPin,
    @Pattern(regexp = "bn|en") String locale,
    @Size(max = 20) Map<@Size(max = 64) String, @Size(max = 256) String> attributes,
    @Valid Partner partner,
    @Valid BankAccount debitParty) {

  /** A biller code (upper case, digits, underscore). */
  public static final String BILLER_CODE = "[A-Z0-9_]{1,32}";
  /** A bill or customer number at a biller. */
  public static final String BILL_NUMBER = "[0-9A-Za-z-]{1,32}";
  /** A bank account number (digits only). */
  public static final String ACCOUNT_NUMBER = "[0-9]{6,20}";
  /** Mobile operators (TOPUP). */
  public static final java.util.Set<String> OPERATORS = java.util.Set.of("gp", "robi", "banglalink", "teletalk");
  /** Partner banks the app can add money from (ADD_MONEY_BANK, ticket 23). */
  public static final java.util.Set<String> BANKS = java.util.Set.of("c2wbank");

  /** A request without a bank account (every type but ADD_MONEY_BANK). */
  public TransactionRequest(
      String txnType,
      CounterParty creditParty,
      Amount amount,
      EncryptedPin encryptedPin,
      String locale,
      Map<String, String> attributes,
      Partner partner) {
    this(txnType, creditParty, amount, encryptedPin, locale, attributes, partner, null);
  }

  /** A request without a partner (P2P and the other wallet flows). */
  public TransactionRequest(
      String txnType,
      CounterParty creditParty,
      Amount amount,
      EncryptedPin encryptedPin,
      String locale,
      Map<String, String> attributes) {
    this(txnType, creditParty, amount, encryptedPin, locale, attributes, null, null);
  }

  /**
   * ADD_MONEY_BANK: the customer's linked bank account the money comes from ({@code type}
   * {@code BANK_ACCOUNT}); masked last 4 only.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record BankAccount(
      @NotBlank @Pattern(regexp = "BANK_ACCOUNT") String type,
      @NotBlank @Pattern(regexp = ACCOUNT_NUMBER) @Sensitive(SensitiveType.ACCOUNT) String id) {}

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
      @Pattern(regexp = "gp|robi|banglalink|teletalk|c2wbank") String partnerId,
      @Pattern(regexp = "PREPAID|POSTPAID") String productCode,
      @Pattern(regexp = BILLER_CODE) String billerCode) {

    public Partner(String partnerId, String productCode) {
      this(partnerId, productCode, null);
    }

    public static Partner biller(String billerCode) {
      return new Partner(null, null, billerCode);
    }

    public static Partner bank(String partnerId) {
      return new Partner(partnerId, null, null);
    }

    /** Operator and product both present, no biller: a TOPUP partner. */
    public boolean isOperator() {
      return partnerId != null && OPERATORS.contains(partnerId) && productCode != null && billerCode == null;
    }

    /** A partner bank only, no product or biller: an ADD_MONEY_BANK partner. */
    public boolean isBank() {
      return partnerId != null && BANKS.contains(partnerId) && productCode == null && billerCode == null;
    }

    /** Biller only: a BILL_PAY partner. */
    public boolean isBiller() {
      return billerCode != null && partnerId == null && productCode == null;
    }
  }
}
