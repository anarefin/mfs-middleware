package mfs.mw.admin.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The full config of one partner (ticket 38, spec 12.1, ADR D28), in the field names of the
 * adapters' partner record ({@code partner/config/PartnerConfig} in mw-adapter-telco, -billpay,
 * -bank): published unchanged as the {@code value} of a {@code mw.config.changed.v1} PARTNER record.
 * Holds no secret: {@code auth.credentialsRef} names one of the partner's secrets in Vault.
 * Missing optional fields are left out (the adapters apply their defaults).
 *
 * <p>Every partner is a route (ADR D28; the old template shape went with ticket 84): {@code route}
 * (Camel YAML DSL built from the mw-* Kamelets), its named JSONata {@code mappings}, the {@code
 * allowedHosts} it may call and the {@code dryRun} a new route must pass before it goes live. {@code
 * family} is not here: the approval adds the partner's family to the published value.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PartnerConfigValue(
    Boolean enabled,
    Long connectTimeoutMs,
    Long timeoutMs,
    Long readTimeoutMs,
    Map<String, String> headers,
    Auth auth,
    Map<String, ErrorMapping> errorMap,
    List<String> ambiguousCodes,
    Map<String, String> sensitiveFields,
    String resilience,
    String route,
    Map<String, String> mappings,
    List<String> allowedHosts,
    DryRun dryRun) {

  public PartnerConfigValue {
    mappings = copy(mappings);
    allowedHosts = allowedHosts == null ? null : List.copyOf(allowedHosts);
    headers = copy(headers);
    errorMap = copy(errorMap);
    ambiguousCodes = ambiguousCodes == null ? null : List.copyOf(ambiguousCodes);
    sensitiveFields = copy(sensitiveFields);
  }

  /** True when the config holds a route. */
  public boolean hasRoute() {
    return route != null;
  }

  /** Same config with the route fields given (read back from {@code partner.partner_route}). */
  public PartnerConfigValue withRoute(String newRoute, Map<String, String> newMappings, List<String> hosts, DryRun newDryRun) {
    return new PartnerConfigValue(
        enabled, connectTimeoutMs, timeoutMs, readTimeoutMs, headers, auth, errorMap, ambiguousCodes, sensitiveFields,
        resilience, newRoute, newMappings, hosts, newDryRun);
  }

  /** Same config without a dry-run: a version whose route is already proven loads at once. */
  public PartnerConfigValue withoutDryRun() {
    return withRoute(route, mappings, allowedHosts, null);
  }

  /** Whether calls are allowed (the adapters' default when unset: true). */
  public boolean isEnabled() {
    return enabled == null || enabled;
  }

  public PartnerConfigValue withEnabled(boolean value) {
    return new PartnerConfigValue(
        value, connectTimeoutMs, timeoutMs, readTimeoutMs, headers, auth, errorMap, ambiguousCodes, sensitiveFields,
        resilience, route, mappings, allowedHosts, dryRun);
  }

  /** The timeouts given replace the held ones; null leaves one as it is. */
  public PartnerConfigValue withTimeouts(Long connect, Long timeout, Long read) {
    return new PartnerConfigValue(
        enabled, connect != null ? connect : connectTimeoutMs, timeout != null ? timeout : timeoutMs,
        read != null ? read : readTimeoutMs, headers, auth, errorMap, ambiguousCodes, sensitiveFields, resilience,
        route, mappings, allowedHosts, dryRun);
  }

  private static <V> Map<String, V> copy(Map<String, V> in) {
    return in == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(in));
  }

  /** Callback auth scheme ({@code none}, {@code api-key}, {@code hmac-sha256}), secret name, non-secret params. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Auth(String scheme, String credentialsRef, Map<String, String> params) {
    public Auth {
      params = copy(params);
    }
  }

  /**
   * The dry-run a route version must pass in its adapter before it goes live (ticket 83): {@code
   * operation} (PAY, INQUIRY, STATUS_QUERY) with {@code sample} as the canonical input, against
   * {@code baseUrl} (the partner's sandbox; must be in allowedHosts) or the environment's simulator.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record DryRun(String operation, Map<String, Object> sample, String baseUrl) {
    public DryRun {
      sample = copy(sample);
    }
  }

  /** Canonical code suffix ({@code MW-PTR-<PARTNER>-<suffix>}) and retryability of one partner code. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ErrorMapping(String suffix, boolean retryable) {}
}
