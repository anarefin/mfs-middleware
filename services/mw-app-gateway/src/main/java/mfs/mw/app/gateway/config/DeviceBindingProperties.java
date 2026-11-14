package mfs.mw.app.gateway.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.app-gateway.device-binding.*}: the device gate on {@code /app/**} (spec 9.1, 18.6;
 * ticket 29, slimmed to a verifier in ticket 68: binding, OTP and sessions are mw-auth's).
 *
 * @param enabled the gate on {@code /app/**}; off only in tests that are not about it
 * @param clockSkew how far {@code X-Timestamp} may be from the gateway's clock (strictly less than
 *     5 min either way); nonces are remembered for twice that plus {@link #NONCE_TTL_MARGIN}, so a
 *     nonce is never forgotten while its timestamp is acceptable
 * @param deviceKeyTtl how long a bound device's wallet and key from mw-auth are reused ({@code
 *     devkey:{deviceId}}, 5 min); a device unbound meanwhile is still refused by the session check
 * @param sessionCacheTtl how long a live session mw-auth confirmed is reused ({@code
 *     sesschk:{walletId}:{deviceId}}, 10 s; 0 asks on every request): an ended session can pass
 *     for at most this long
 */
@Validated
@ConfigurationProperties("mw.app-gateway.device-binding")
public record DeviceBindingProperties(
    boolean enabled, @NotNull Duration clockSkew, @NotNull Duration deviceKeyTtl, @NotNull Duration sessionCacheTtl) {

  /** Kept on top of the 2x skew window: clock steps between instances and Valkey expiry jitter. */
  public static final Duration NONCE_TTL_MARGIN = Duration.ofSeconds(30);

  /**
   * A timestamp is accepted from {@code skew} before to {@code skew} after the time it names, so a
   * nonce first used anywhere in that window must be remembered at least 2x skew; plus a margin.
   */
  public Duration nonceTtl() {
    return clockSkew.multipliedBy(2).plus(NONCE_TTL_MARGIN);
  }
}
