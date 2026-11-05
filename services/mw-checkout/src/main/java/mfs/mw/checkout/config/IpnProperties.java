package mfs.mw.checkout.config;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code mw.checkout.ipn.*}: the signed IPN to the merchant (spec 9.3 step 3, ticket 32).
 *
 * @param enabled whether the delivery job runs (tests switch it off and call the service)
 * @param pollPeriod how often the job looks for due attempts
 * @param retryDelays the wait after each failed attempt (1 min, 5 min, 30 min, 2 h, 6 h)
 * @param batchSize attempts per job run
 * @param connectTimeout TCP/TLS connect timeout; the whole call is bounded by the Resilience4j
 *     time limiter {@code merchant-ipn}
 * @param devHosts hosts that may be called over plain http and at a private address (dev/test
 *     simulators only; empty by default, never in production)
 */
@Validated
@ConfigurationProperties("mw.checkout.ipn")
public record IpnProperties(
    boolean enabled,
    @NotNull Duration pollPeriod,
    @NotEmpty List<Duration> retryDelays,
    @Positive int batchSize,
    @NotNull Duration connectTimeout,
    List<String> devHosts) {

  public IpnProperties {
    retryDelays = retryDelays == null ? List.of() : List.copyOf(retryDelays);
    devHosts =
        devHosts == null ? List.of() : devHosts.stream().map(String::trim).filter(h -> !h.isEmpty()).toList();
  }
}
