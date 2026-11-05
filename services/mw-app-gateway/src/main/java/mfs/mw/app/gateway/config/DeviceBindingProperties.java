package mfs.mw.app.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.app-gateway.device-binding.*}: device binding, request signatures and sessions (spec
 * 9.1, 18.6, ticket 29).
 *
 * @param enabled the gate on {@code /app/**}; off only in tests that are not about it
 * @param clockSkew how far {@code X-Timestamp} may be from the gateway's clock (strictly less than
 *     5 min either way); nonces are remembered for twice that plus {@link #NONCE_TTL_MARGIN}, so a
 *     nonce is never forgotten while its timestamp is acceptable
 * @param idleTimeout session idle timeout ({@code sess:{subject}:{deviceId}}), 5 min
 * @param idleTimeoutByChannel per app ({@code CUSTOMER_APP}, {@code AGENT_APP}), overrides {@code
 *     idleTimeout}
 * @param singleActiveDevice one active device per wallet: binding a device replaces the others and
 *     opening a session ends theirs
 * @param otp the binding OTP sent by SMS to the token's wallet (review A5)
 * @param notifyTopic mw-notify's request topic (the OTP and the "new device bound" SMS)
 */
@Validated
@ConfigurationProperties("mw.app-gateway.device-binding")
public record DeviceBindingProperties(
    boolean enabled,
    @NotNull Duration clockSkew,
    @NotNull Duration idleTimeout,
    Map<String, Duration> idleTimeoutByChannel,
    boolean singleActiveDevice,
    @NotNull @Valid Otp otp,
    @NotBlank String notifyTopic) {

  /**
   * @param ttl validity of an OTP (mw-notify's OTP default expiry, 2 min)
   * @param maxAttempts checks per OTP; the wrong guess that uses the last one voids it
   * @param maxSends OTPs per wallet within {@code sendWindow}
   * @param sendWindow the window the sends are counted in
   */
  public record Otp(
      @NotNull Duration ttl, @Positive int maxAttempts, @Positive int maxSends, @NotNull Duration sendWindow) {}

  public DeviceBindingProperties {
    idleTimeoutByChannel = Map.copyOf(idleTimeoutByChannel == null ? Map.of() : idleTimeoutByChannel);
  }

  public Duration idleTimeout(String channel) {
    return idleTimeoutByChannel.getOrDefault(channel, idleTimeout);
  }

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
