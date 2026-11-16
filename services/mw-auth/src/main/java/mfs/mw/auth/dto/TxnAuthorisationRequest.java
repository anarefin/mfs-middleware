package mfs.mw.auth.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import mfs.mw.auth.util.MaskingPolicy;
import mfs.mw.auth.util.Msisdn;
import mfs.mw.auth.util.Sensitive;
import mfs.mw.auth.util.SensitiveType;

/**
 * Body of {@code POST /internal/v1/txn-authorisations} (contract {@code auth-internal.v1}
 * TxnAuthorisationRequest; spec 18.7, D22): the wallet (its id from the app, or its MSISDN from USSD
 * and checkout: exactly one), the channel, the app's device, the PIN block and the transaction as it
 * will be published on {@code mw.txn.request.v1}.
 */
public record TxnAuthorisationRequest(
    @Size(min = 1, max = 64) @Sensitive(SensitiveType.MSISDN) String walletId,
    @Pattern(regexp = Msisdn.PATTERN) @Sensitive(SensitiveType.MSISDN) String msisdn,
    @NotBlank @Pattern(regexp = "[A-Z][A-Z_]{1,31}") String channel,
    @Size(min = 1, max = 128) String deviceId,
    @NotNull @Valid EncryptedPin encryptedPin,
    @NotNull @Valid Txn txn) {

  /**
   * The transaction the assertion is bound to (payer = debitParty, payee = creditParty). The amount is
   * at least 1, except txnType BALANCE (ADR D26): 0 with payee = payer ({@code service/TxnAmountRule}).
   */
  public record Txn(
      @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,31}") String txnType,
      @PositiveOrZero long amount,
      @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
      @NotNull @Valid Party payer,
      @NotNull @Valid Party payee,
      @NotBlank @Size(max = 64) String idempotencyKey) {

    @Override
    public String toString() {
      return MaskingPolicy.describe(this);
    }
  }

  /** A party as hashed: its type and id. */
  public record Party(
      @NotBlank @Pattern(regexp = "[A-Z][A-Z_]{1,31}") String type,
      @NotBlank @Size(max = 128) @Sensitive(SensitiveType.PARTY_ID) String id) {

    @Override
    public String toString() {
      return MaskingPolicy.describe(this);
    }
  }

  @Override
  public String toString() {
    return MaskingPolicy.describe(this);
  }
}
