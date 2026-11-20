package mfs.mw.orchestrator.config;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Merchants with their own merchant system (ticket 115b, FRS UC-11, EXT-11): {@code
 * mw.orchestrator.merchant-systems.merchants.<merchant wallet id>} names the partner record of
 * mw-adapter-merchant ({@code partnerId}, e.g. {@code merchant-soap}), the merchant's id in that
 * system and its till. A MERCHANT_PAY to such a wallet is validated by the merchant system before the
 * core is asked, and the merchant is notified after COMPLETED: {@code notifyAttempts} sends in all
 * (the first plus retries), then the notify is dead-lettered (audit + alert); the payment stays.
 * A retry waits {@code notifyBackoff} after the first failed answer, doubling for each later one
 * (M9 review; default 30 s, so 30 s then 60 s), through the saga timer {@code MERCHANT_NOTIFY}.
 */
@ConfigurationProperties("mw.orchestrator.merchant-systems")
public record MerchantSystemProperties(
    @DefaultValue Map<String, Merchant> merchants,
    @DefaultValue("3") int notifyAttempts,
    @DefaultValue("30s") Duration notifyBackoff) {

  public record Merchant(String partnerId, String merchantId, String tillId) {}

  /** The merchant system behind the wallet {@code walletId}, if it has one. */
  public Optional<Merchant> forWallet(String walletId) {
    return walletId == null || merchants == null ? Optional.empty() : Optional.ofNullable(merchants.get(walletId));
  }
}
