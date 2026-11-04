package mfs.mw.core.mock.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Core-mock rules and seed, all under {@code mw.coremock} (defaults in {@code application.yml}).
 * Amounts are minor units (poisha).
 *
 * @param fees flat fee per {@code txnType}; a type not listed has no fee
 * @param limits per-transaction and daily debit limits
 * @param seed what the ledger is seeded with on start
 * @param pin the PIN key pair (private key from OpenBao in a stack)
 * @param walletTypes per {@code txnType}, the wallet types the debit and the credit wallet must
 *     have (agent cash in: AGENT -> CUSTOMER, cash out: CUSTOMER -> AGENT); a type not listed is
 *     not restricted. Refused with {@code INVALID_COUNTERPARTY}.
 * @param pinMaxFailures consecutive wrong PINs after which a wallet is locked (spec 9.1: 5)
 */
@Validated
@ConfigurationProperties("mw.coremock")
public record CoreMockProperties(
    @NotNull Map<String, Long> fees,
    @Valid @NotNull Limits limits,
    @Valid @NotNull Seed seed,
    @Valid @NotNull Pin pin,
    Map<String, @Valid WalletTypes> walletTypes,
    @Positive int pinMaxFailures) {

  public CoreMockProperties {
    walletTypes = Map.copyOf(walletTypes == null ? Map.of() : walletTypes);
  }

  /** The wallet type the debit and the credit wallet of one {@code txnType} must have. */
  public record WalletTypes(@NotBlank String debit, @NotBlank String credit) {}

  /**
   * Debit limits for every wallet; {@code walletTypes} overrides the daily limit per wallet type
   * (an org pays out a whole bulk batch in a day, ticket 34).
   */
  public record Limits(
      @Positive long perTxnMinor, @Positive long dailyMinor, Map<String, @Valid TypeLimit> walletTypes) {

    public Limits {
      walletTypes = Map.copyOf(walletTypes == null ? Map.of() : walletTypes);
    }

    /** The daily debit limit of a wallet of {@code walletType}. */
    public long dailyMinor(String walletType) {
      TypeLimit t = walletTypes.get(walletType);
      return t == null ? dailyMinor : t.dailyMinor();
    }
  }

  /** The daily debit limit of one wallet type. */
  public record TypeLimit(@Positive long dailyMinor) {}

  /**
   * Seed settings. The PIN is a fake, shared by all seeded wallets (test double only).
   *
   * @param enabled seed on start
   * @param pin plaintext PIN whose bcrypt hash every seeded wallet gets
   */
  public record Seed(
      boolean enabled,
      @NotBlank String pin,
      @PositiveOrZero long customerBalanceMinor,
      @PositiveOrZero long agentBalanceMinor,
      @PositiveOrZero long merchantBalanceMinor,
      @PositiveOrZero long orgBalanceMinor) {}

  /**
   * PIN encryption key pair.
   *
   * @param keyId id the channels put in {@code encryptedPin.keyId}
   * @param privateKey PKCS#8 private key, PEM or bare base64 (OpenBao KV in a stack); blank = none
   * @param bcryptStrength bcrypt cost for the seed PIN hash (checks use the hash's own cost)
   */
  public record Pin(
      @NotBlank String keyId,
      String privateKey,
      @jakarta.validation.constraints.Min(4) @jakarta.validation.constraints.Max(14)
          int bcryptStrength) {}
}
