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
 *
 * <p>Health and maintenance (tickets 97, 131b): {@code maintenance} (in maintenance now, until
 * cleared), {@code healthCheck} (the route has a HEALTH_CHECK branch the adapter probes) and {@code
 * maintenanceWindow} (start, end, bn/en message; inside it the adapter refuses calls with {@code
 * MW-SYS-PARTNER_MAINTENANCE}). Endpoint profile (ticket 131b): a route calls {@code url={{endpoint}}},
 * which the adapter fills from {@code endpoints} ({@code MOCK}, {@code REAL}) by the selected {@code
 * endpoint}; switching is a config change, no route edit. Every one is carried through every
 * republish (a change of another field never drops them).
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
    DryRun dryRun,
    Boolean maintenance,
    Boolean healthCheck,
    MaintenanceWindow maintenanceWindow,
    String endpoint,
    Map<String, String> endpoints) {

  /** The route placeholder the adapter fills with the selected endpoint's base URL (ticket 131b). */
  public static final String ENDPOINT_PLACEHOLDER = "{{endpoint}}";

  /** The endpoint profiles (ticket 131b, FRS 10.4 criterion 3). */
  public static final java.util.Set<String> ENDPOINT_PROFILES = java.util.Set.of("MOCK", "REAL");

  public PartnerConfigValue {
    mappings = copy(mappings);
    allowedHosts = allowedHosts == null ? null : List.copyOf(allowedHosts);
    headers = copy(headers);
    errorMap = copy(errorMap);
    ambiguousCodes = ambiguousCodes == null ? null : List.copyOf(ambiguousCodes);
    sensitiveFields = copy(sensitiveFields);
    endpoints = copy(endpoints);
  }

  /** A config without the ticket 97 / 131b fields. */
  public PartnerConfigValue(
      Boolean enabled, Long connectTimeoutMs, Long timeoutMs, Long readTimeoutMs, Map<String, String> headers,
      Auth auth, Map<String, ErrorMapping> errorMap, List<String> ambiguousCodes, Map<String, String> sensitiveFields,
      String resilience, String route, Map<String, String> mappings, List<String> allowedHosts, DryRun dryRun) {
    this(enabled, connectTimeoutMs, timeoutMs, readTimeoutMs, headers, auth, errorMap, ambiguousCodes, sensitiveFields,
        resilience, route, mappings, allowedHosts, dryRun, null, null, null, null, null);
  }

  /**
   * The route as the adapter loads it: {@value #ENDPOINT_PLACEHOLDER} replaced by the selected
   * endpoint's base URL (ticket 131b); the route itself when no endpoint is selected or it has no URL.
   */
  public String resolvedRoute() {
    if (route == null || endpoint == null || endpoints == null || endpoints.get(endpoint) == null) {
      return route;
    }
    return route.replace(ENDPOINT_PLACEHOLDER, endpoints.get(endpoint).replaceAll("/+$", ""));
  }

  /** Same config with the ticket 97 / 131b fields of {@code other}. */
  public PartnerConfigValue withHealthFields(PartnerConfigValue other) {
    return new PartnerConfigValue(
        enabled, connectTimeoutMs, timeoutMs, readTimeoutMs, headers, auth, errorMap, ambiguousCodes, sensitiveFields,
        resilience, route, mappings, allowedHosts, dryRun, other.maintenance, other.healthCheck, other.maintenanceWindow,
        other.endpoint, other.endpoints);
  }

  /** Same config with another maintenance window (ticket 131b); null clears it. */
  public PartnerConfigValue withMaintenance(Boolean flag, MaintenanceWindow window) {
    return new PartnerConfigValue(
        enabled, connectTimeoutMs, timeoutMs, readTimeoutMs, headers, auth, errorMap, ambiguousCodes, sensitiveFields,
        resilience, route, mappings, allowedHosts, dryRun, flag, healthCheck, window, endpoint, endpoints);
  }

  /** Same config with another endpoint profile and endpoint URLs (ticket 131b). */
  public PartnerConfigValue withEndpoint(String profile, Map<String, String> urls) {
    return new PartnerConfigValue(
        enabled, connectTimeoutMs, timeoutMs, readTimeoutMs, headers, auth, errorMap, ambiguousCodes, sensitiveFields,
        resilience, route, mappings, allowedHosts, dryRun, maintenance, healthCheck, maintenanceWindow, profile, urls);
  }

  /** True when the config holds a route. */
  public boolean hasRoute() {
    return route != null;
  }

  /** Same config with the route fields given (read back from {@code partner.partner_route}). */
  public PartnerConfigValue withRoute(String newRoute, Map<String, String> newMappings, List<String> hosts, DryRun newDryRun) {
    return new PartnerConfigValue(
        enabled, connectTimeoutMs, timeoutMs, readTimeoutMs, headers, auth, errorMap, ambiguousCodes, sensitiveFields,
        resilience, newRoute, newMappings, hosts, newDryRun, maintenance, healthCheck, maintenanceWindow, endpoint,
        endpoints);
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
        resilience, route, mappings, allowedHosts, dryRun, maintenance, healthCheck, maintenanceWindow, endpoint,
        endpoints);
  }

  /** The timeouts given replace the held ones; null leaves one as it is. */
  public PartnerConfigValue withTimeouts(Long connect, Long timeout, Long read) {
    return new PartnerConfigValue(
        enabled, connect != null ? connect : connectTimeoutMs, timeout != null ? timeout : timeoutMs,
        read != null ? read : readTimeoutMs, headers, auth, errorMap, ambiguousCodes, sensitiveFields, resilience,
        route, mappings, allowedHosts, dryRun, maintenance, healthCheck, maintenanceWindow, endpoint, endpoints);
  }

  /** The error map given replaces the held one (ticket 131); null ambiguous codes keep the held ones. */
  public PartnerConfigValue withErrorMap(Map<String, ErrorMapping> map, List<String> ambiguous) {
    return new PartnerConfigValue(
        enabled, connectTimeoutMs, timeoutMs, readTimeoutMs, headers, auth, map,
        ambiguous != null ? ambiguous : ambiguousCodes, sensitiveFields, resilience, route, mappings, allowedHosts,
        dryRun, maintenance, healthCheck, maintenanceWindow, endpoint, endpoints);
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

  /**
   * A maintenance window (ticket 131b, FRS PLT-043): from {@code start} (absent: now) until {@code
   * end} (absent: until cleared); the bn/en message the refusal carries (absent: the adapters'
   * default text).
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record MaintenanceWindow(java.time.Instant start, java.time.Instant end, String messageEn, String messageBn) {}

  /** Canonical code suffix ({@code MW-PTR-<PARTNER>-<suffix>}) and retryability of one partner code. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ErrorMapping(String suffix, boolean retryable) {

    /** {@code retryable} is optional in the contract (default false; ticket 131's error-map screen leaves it out). */
    @com.fasterxml.jackson.annotation.JsonCreator
    public static ErrorMapping of(
        @com.fasterxml.jackson.annotation.JsonProperty("suffix") String suffix,
        @com.fasterxml.jackson.annotation.JsonProperty("retryable") Boolean retryable) {
      return new ErrorMapping(suffix, Boolean.TRUE.equals(retryable));
    }
  }
}
