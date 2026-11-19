package mfs.mw.core.mock.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import mfs.mw.core.mock.entity.Wallet;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Core-mock rules and seed, all under {@code mw.coremock} (defaults in {@code application.yml}).
 * Amounts are minor units (poisha). The core holds no PIN (ticket 72): customer authorisation is
 * mw-auth's assertion ({@link AssertionProperties}). Fees and limits are the tariff and limit sheets
 * seeded from {@code seed/<version>/} (ticket 94), no longer properties.
 *
 * @param seed what the ledger is seeded with on start
 * @param walletTypes per {@code txnType}, the wallet types the debit and the credit wallet must
 *     have (agent cash in: AGENT -> CUSTOMER, cash out: CUSTOMER -> AGENT); a type not listed is
 *     not restricted. Refused with {@code INVALID_COUNTERPARTY}. {@code ORG} reads as ORGANISATION.
 * @param holdTtl how long a hold lives before the core releases it (FRS 7.3, default 30 min)
 * @param holdExpiry the job that releases expired holds
 */
@Validated
@ConfigurationProperties("mw.coremock")
public record CoreMockProperties(
    @Valid @NotNull Seed seed,
    Map<String, @Valid WalletTypes> walletTypes,
    Duration holdTtl,
    @Valid HoldExpiry holdExpiry) {

  public CoreMockProperties {
    Map<String, WalletTypes> types = new LinkedHashMap<>();
    (walletTypes == null ? Map.<String, WalletTypes>of() : walletTypes).forEach(
        (k, v) -> types.put(k, new WalletTypes(Wallet.canonicalType(v.debit()), Wallet.canonicalType(v.credit()))));
    walletTypes = Map.copyOf(types);
    holdTtl = holdTtl == null ? Duration.ofMinutes(30) : holdTtl;
    holdExpiry = holdExpiry == null ? new HoldExpiry(500) : holdExpiry;
  }

  /** The wallet type the debit and the credit wallet of one {@code txnType} must have. */
  public record WalletTypes(@NotBlank String debit, @NotBlank String credit) {}

  /**
   * Seed settings.
   *
   * @param enabled seed on start
   * @param version the folder under {@code seed/} the files come from ({@code v1})
   */
  public record Seed(boolean enabled, String version) {

    public Seed {
      version = version == null || version.isBlank() ? "v1" : version;
    }
  }

  /** At most {@code batchSize} holds per run of the expiry job (its cron: {@code mw.coremock.hold-expiry.cron}). */
  public record HoldExpiry(@Positive int batchSize) {}
}
