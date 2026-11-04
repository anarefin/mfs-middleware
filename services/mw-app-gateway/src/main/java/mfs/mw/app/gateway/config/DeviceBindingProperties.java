package mfs.mw.app.gateway.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.app-gateway.device-binding.*}: device binding, request signatures and sessions (spec
 * 9.1, 18.6, ticket 29).
 *
 * @param enabled the gate on {@code /app/**}; off only in tests that are not about it
 * @param clockSkew how far {@code X-Timestamp} may be from the gateway's clock (±5 min); nonces are
 *     remembered twice as long, so a nonce is never forgotten while its timestamp is acceptable
 * @param idleTimeout session idle timeout ({@code sess:{subject}:{deviceId}}), 5 min
 * @param idleTimeoutByChannel per app ({@code CUSTOMER_APP}, {@code AGENT_APP}), overrides {@code
 *     idleTimeout}
 * @param singleActiveDevice one active device per wallet: binding a device replaces the others and
 *     opening a session ends theirs
 */
@Validated
@ConfigurationProperties("mw.app-gateway.device-binding")
public record DeviceBindingProperties(
    boolean enabled,
    @NotNull Duration clockSkew,
    @NotNull Duration idleTimeout,
    Map<String, Duration> idleTimeoutByChannel,
    boolean singleActiveDevice) {

  public DeviceBindingProperties {
    idleTimeoutByChannel = Map.copyOf(idleTimeoutByChannel == null ? Map.of() : idleTimeoutByChannel);
  }

  public Duration idleTimeout(String channel) {
    return idleTimeoutByChannel.getOrDefault(channel, idleTimeout);
  }

  public Duration nonceTtl() {
    return clockSkew.multipliedBy(2);
  }
}
