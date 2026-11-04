package mfs.mw.adapter.telco.config;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Static OCS configuration (ticket 16; the adapter framework replaces it in ticket 20).
 *
 * @param baseUrl OCS REST base URL
 * @param connectTimeout TCP connect timeout; a failed connect means nothing was sent
 * @param readTimeout socket read timeout; keep it a little above the time limiter so the time
 *     limiter (resilience4j.timelimiter.instances.ocs, 20 s) decides first
 * @param ambiguousCodes OCS codes that do not say whether the top-up was applied (-> UNKNOWN)
 * @param errorCodes OCS code -> canonical code suffix (MW-PTR-&lt;PARTNER&gt;-&lt;suffix&gt;) and
 *     retryability; an unlisted code maps to {@code UNMAPPED}, not retryable
 */
@Validated
@ConfigurationProperties("mw.telco.ocs")
public record OcsProperties(
    @NotNull URI baseUrl,
    @NotNull Duration connectTimeout,
    @NotNull Duration readTimeout,
    @NotNull Set<String> ambiguousCodes,
    @NotNull Map<String, ErrorMapping> errorCodes) {

  public OcsProperties {
    ambiguousCodes = Set.copyOf(ambiguousCodes);
    errorCodes = Map.copyOf(errorCodes);
  }

  /** Canonical code suffix (upper case letters, digits, underscore) and retryability. */
  public record ErrorMapping(String suffix, boolean retryable) {}
}
