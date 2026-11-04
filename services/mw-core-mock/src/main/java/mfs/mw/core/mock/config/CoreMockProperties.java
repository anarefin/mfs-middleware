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
 */
@Validated
@ConfigurationProperties("mw.coremock")
public record CoreMockProperties(
    @NotNull Map<String, Long> fees,
    @Valid @NotNull Limits limits,
    @Valid @NotNull Seed seed,
    @Valid @NotNull Pin pin) {

  /** Debit limits for every wallet. */
  public record Limits(@Positive long perTxnMinor, @Positive long dailyMinor) {}

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
