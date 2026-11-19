package mfs.mw.adapter.telco.partner.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import mfs.mw.adapter.telco.partner.mapping.CompiledMapping;
import mfs.mw.adapter.telco.util.SensitiveType;

/**
 * One partner record (ADR D28, spec 12.1): the partner's Camel YAML DSL {@code route} built from
 * the shipped mw-* Kamelets, the JSONata {@code mappings} it names, the hosts it may call, and the
 * error-code map, timeouts and breaker settings the Kamelets read. Bound from {@code
 * mw.partners.instances.<id>} (seed) and read from the {@code value} of a {@code
 * mw.config.changed.v1} PARTNER record, with the same field names. Holds no secret: a route names a
 * secret field of its own partner ({@code secretField}), and {@code auth.credentialsRef} names the
 * one a callback is verified with. Missing optional fields get their defaults here, so every field
 * is non-null after construction (except {@code route}, {@code family}, {@code resilience} and
 * {@code dryRun}). The old shape ({@code template}, {@code baseUrl}, {@code operations}, tickets 20
 * to 83) is gone since ticket 84: a record without a {@code route} is refused.
 *
 * @param enabled false: every call is refused unsent ({@code NOT_SENT DISABLED})
 * @param route the partner's route, Camel YAML DSL ({@code from: direct:partner-<id>}), checked by
 *     {@link mfs.mw.adapter.telco.partner.route.PartnerRouteLoader}
 * @param mappings JSONata mappings by name, used by {@code mw-map} and {@code mw-classify}
 *     ({@code <OPERATION>.request}, {@code <OPERATION>.response}; {@code CALLBACK.response} maps a
 *     callback); compiled once per record version
 * @param allowedHosts the hosts this partner's route may call (approved with the record); the
 *     static {@code mw.partners.security.allowed-hosts} of the partner, when set, narrows them
 * @param family partner family ({@code telco}, {@code billpay} or {@code bank}); an adapter skips
 *     a record of another family
 * @param inquiryRetries how often an INQUIRY or STATUS_QUERY is sent again after a timeout, a 5xx,
 *     a reset or a failed connect (default 1); a PAY is never sent again
 * @param resilience circuit breaker and bulkhead of this partner; null: the {@code resilience4j}
 *     instance named by the partner id (or the defaults)
 * @param dryRun the operation and sample the admin console's dry run sends (ticket 83)
 * @param connectTimeoutMs TCP connect timeout (default 3000); a failed connect means not sent
 * @param timeoutMs time limit per call (default 30000, spec 11.1); past it the outcome is UNKNOWN
 * @param readTimeoutMs socket read timeout (default timeoutMs + 2000, so the limit decides first)
 * @param headers static headers sent on every call
 * @param errorMap partner code to canonical code suffix ({@code MW-PTR-<PARTNER>-<suffix>}) and
 *     retryability; an unlisted code is {@code UNMAPPED}, not retryable
 * @param ambiguousCodes partner codes that do not say whether the request was applied (UNKNOWN)
 * @param sensitiveFields JSON field name to masking rule for the audit of raw bodies (free-text
 *     scrubbing applies on top)
 * @param maintenance true: the partner is in a maintenance window (ticket 97; health {@code
 *     MAINTENANCE}, so routing (98) sends nothing there; calls are not refused by the engine). A flag
 *     for now; ticket 131 adds the console's window
 * @param healthCheck true: the route has a {@code HEALTH_CHECK} branch, probed on a schedule
 *     (ticket 97, {@code PartnerHealthMonitor}); a failing probe makes the partner {@code DOWN}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PartnerConfig(
    Boolean enabled,
    String route,
    Map<String, String> mappings,
    List<String> allowedHosts,
    String family,
    Integer inquiryRetries,
    Resilience resilience,
    DryRun dryRun,
    Long connectTimeoutMs,
    Long timeoutMs,
    Long readTimeoutMs,
    Map<String, String> headers,
    Auth auth,
    Map<String, ErrorMapping> errorMap,
    Set<String> ambiguousCodes,
    Map<String, SensitiveType> sensitiveFields,
    Boolean maintenance,
    Boolean healthCheck) {

  private static final Pattern SUFFIX = Pattern.compile("^[A-Z0-9_]+$");

  /** A mapping name: letters, digits, dot, underscore, hyphen (it appears in a Kamelet URI). */
  public static final Pattern MAPPING_NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");

  /** Prefix of a problem that breaks a security rule (logged as a security event when refused). */
  public static final String SECURITY = "security: ";

  /** The operations a dry-run may send. */
  public static final Set<String> DRY_RUN_OPERATIONS = Set.of("PAY", "INQUIRY", "STATUS_QUERY");

  /**
   * A URL path (a dry-run sandbox's base path): absolute, plain characters and the {@code {reference}} placeholder only. No
   * {@code @ : ? # \ %}, no {@code ..} segment, no {@code //}: nothing that could move the request
   * to another host, port or user info once appended to the base URL.
   */
  static final Pattern SAFE_PATH = Pattern.compile("^/[A-Za-z0-9._~/{}-]*$");

  /** The canonical constructor, the one config binding uses (the record has a second one). */
  @org.springframework.boot.context.properties.bind.ConstructorBinding
  public PartnerConfig {
    enabled = enabled == null || enabled;
    route = route == null || route.isBlank() ? null : route;
    mappings = mappings == null ? Map.of() : Map.copyOf(mappings);
    allowedHosts = allowedHosts == null ? List.of() : allowedHosts.stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).filter(h -> !h.isEmpty()).toList();
    inquiryRetries = inquiryRetries == null ? 1 : inquiryRetries;
    connectTimeoutMs = connectTimeoutMs == null ? 3000L : connectTimeoutMs;
    timeoutMs = timeoutMs == null ? 30_000L : timeoutMs;
    readTimeoutMs = readTimeoutMs == null ? timeoutMs + 2000L : readTimeoutMs;
    headers = headers == null ? Map.of() : Map.copyOf(headers);
    auth = auth == null ? new Auth(null, null, null) : auth;
    errorMap = errorMap == null ? Map.of() : Map.copyOf(errorMap);
    ambiguousCodes = ambiguousCodes == null ? Set.of() : Set.copyOf(ambiguousCodes);
    sensitiveFields = sensitiveFields == null ? Map.of() : Map.copyOf(sensitiveFields);
    maintenance = Boolean.TRUE.equals(maintenance);
    healthCheck = Boolean.TRUE.equals(healthCheck);
  }

  /** A record without the ticket 97 health fields (not in maintenance, no health check). */
  public PartnerConfig(
      Boolean enabled, String route, Map<String, String> mappings, List<String> allowedHosts, String family,
      Integer inquiryRetries, Resilience resilience, DryRun dryRun, Long connectTimeoutMs, Long timeoutMs,
      Long readTimeoutMs, Map<String, String> headers, Auth auth, Map<String, ErrorMapping> errorMap,
      Set<String> ambiguousCodes, Map<String, SensitiveType> sensitiveFields) {
    this(enabled, route, mappings, allowedHosts, family, inquiryRetries, resilience, dryRun, connectTimeoutMs, timeoutMs,
        readTimeoutMs, headers, auth, errorMap, ambiguousCodes, sensitiveFields, null, null);
  }

  public Duration timeout() {
    return Duration.ofMillis(timeoutMs);
  }

  /** Error-code map entry for a partner code; {@code UNMAPPED}, not retryable, when unlisted. */
  public ErrorMapping errorMapping(String code) {
    return errorMap.getOrDefault(code, ErrorMapping.UNMAPPED);
  }

  /** Same record with another route, mappings and hosts. */
  public PartnerConfig withRoute(String newRoute, Map<String, String> newMappings, List<String> newAllowedHosts) {
    return new PartnerConfig(
        enabled, newRoute, newMappings, newAllowedHosts, family, inquiryRetries, resilience, dryRun, connectTimeoutMs,
        timeoutMs, readTimeoutMs, headers, auth, errorMap, ambiguousCodes, sensitiveFields, maintenance, healthCheck);
  }

  /**
   * Everything that stops this record being used, besides its route (checked by the loader);
   * empty when it is complete and its mappings compile.
   */
  public List<String> problems() {
    List<String> problems = new ArrayList<>();
    if (route == null) {
      problems.add("a route is required");
    }
    if (connectTimeoutMs <= 0 || timeoutMs <= 0 || readTimeoutMs <= 0) {
      problems.add("timeouts must be positive");
    }
    if (inquiryRetries < 0 || inquiryRetries > 3) {
      problems.add("inquiryRetries must be 0 to 3");
    }
    mappings.forEach(
        (name, source) -> {
          if (!MAPPING_NAME.matcher(name).matches()) {
            problems.add("mapping names are letters, digits, . _ -");
          } else {
            compiles("mappings." + name, source, problems);
          }
        });
    errorMap.forEach(
        (code, m) -> {
          if (m == null || m.suffix() == null || !SUFFIX.matcher(m.suffix()).matches()) {
            problems.add("errorMap." + code + ": suffix must be upper case letters, digits, underscore");
          }
        });
    if (dryRun != null && (dryRun.operation() == null || !DRY_RUN_OPERATIONS.contains(dryRun.operation()))) {
      problems.add("dryRun.operation must be one of " + String.join(", ", new java.util.TreeSet<>(DRY_RUN_OPERATIONS)));
    }
    if (dryRun != null && dryRun.baseUrl() != null && !plainBaseUrl(dryRun.baseUrl())) {
      // the value is not echoed: it may be an attempt to reach another host
      problems.add(SECURITY + "dryRun.baseUrl must be an absolute http(s) URL without user info, query, fragment,"
          + " encoding or .. segments (its host is checked against allowedHosts at load)");
    }
    if (!mfs.mw.adapter.telco.partner.auth.AuthSchemes.NAMES.contains(auth.scheme())) {
      problems.add("unknown auth scheme " + auth.scheme());
    } else if (!"none".equals(auth.scheme()) && auth.credentialsRef() == null) {
      problems.add("auth scheme " + auth.scheme() + " needs a credentialsRef");
    }
    if (auth.credentialsRef() != null
        && !mfs.mw.adapter.telco.partner.CredentialRef.NAME.matcher(auth.credentialsRef()).matches()) {
      // the value is not echoed: it may be an attempt to name another secret
      problems.add(
          SECURITY
              + "auth.credentialsRef must be a secret name of this partner (lower-case letters, digits, hyphen)");
    }
    return problems;
  }

  private static void compiles(String field, String source, List<String> problems) {
    if (source == null || source.isBlank()) {
      problems.add(field + " is empty");
      return;
    }
    if (mfs.mw.adapter.telco.partner.mapping.CompiledStylesheet.isStylesheet(field)) {
      stylesheet(field, source, problems);
      return;
    }
    try {
      CompiledMapping.compile(source);
    } catch (IllegalArgumentException e) {
      problems.add(field + " does not compile: " + e.getMessage());
    }
  }

  /**
   * A {@code *.xsl} mapping (ticket 85): an XSLT stylesheet for {@code mw-soap-call}. One that
   * reaches outside itself ({@code document()}, an entity, an include, ...) breaks a security rule;
   * one that does not compile is an ordinary problem.
   */
  private static void stylesheet(String field, String source, List<String> problems) {
    List<String> policy = mfs.mw.adapter.telco.partner.mapping.StylesheetPolicy.problems(source);
    if (!policy.isEmpty()) {
      policy.forEach(p -> problems.add(SECURITY + field + ": " + p));
      return;
    }
    try {
      mfs.mw.adapter.telco.partner.mapping.CompiledStylesheet.compile(source);
    } catch (IllegalArgumentException e) {
      problems.add(field + ": " + e.getMessage());
    }
  }

  /**
   * How a callback of this partner is verified (ticket 22); outbound calls name their auth in the
   * route ({@code mw-http-call?auth=...}).
   *
   * @param scheme {@code none} (default), {@code api-key}, {@code hmac-sha256}
   * @param credentialsRef the secret field of this partner ({@code hmac-secret}), read from Vault
   *     {@code secret/<service>/<partner>}; never the secret, never a path
   * @param params non-secret scheme parameters (header names, key id, max skew)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Auth(String scheme, String credentialsRef, Map<String, String> params) {
    public Auth {
      scheme = scheme == null || scheme.isBlank() ? "none" : scheme;
      params = params == null ? Map.of() : Map.copyOf(params);
    }
  }

  /** An absolute http(s) URL of scheme, host, port and a safe path only. */
  static boolean plainBaseUrl(String url) {
    try {
      URI u = new URI(url);
      String path = u.getRawPath() == null ? "" : u.getRawPath();
      return u.getScheme() != null && Set.of("http", "https").contains(u.getScheme().toLowerCase(Locale.ROOT))
          && u.getHost() != null && u.getRawUserInfo() == null && u.getRawQuery() == null && u.getRawFragment() == null
          && (path.isEmpty() || (safePath(path) && path.indexOf('{') < 0 && path.indexOf('}') < 0));
    } catch (java.net.URISyntaxException e) {
      return false;
    }
  }

  /** True when {@code path} is a safe path (see {@link #SAFE_PATH}). */
  public static boolean safePath(String path) {
    return path != null
        && SAFE_PATH.matcher(path).matches()
        && !path.contains("//")
        && java.util.Arrays.stream(path.split("/")).noneMatch(".."::equals);
  }

  /** Canonical code suffix and retryability of one partner code. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ErrorMapping(String suffix, boolean retryable) {
    public static final ErrorMapping UNMAPPED = new ErrorMapping("UNMAPPED", false);
  }

  /**
   * The partner's circuit breaker and bulkhead; a null field keeps the default of the {@code
   * resilience4j} instance named by the partner id.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Resilience(
      Float failureRateThreshold,
      Integer slidingWindowSize,
      Integer minimumNumberOfCalls,
      Long slowCallDurationThresholdMs,
      Long waitDurationInOpenStateMs,
      Integer bulkheadMaxConcurrentCalls) {}

  /**
   * The dry-run a new version must pass before it goes live (ticket 83): {@code operation} sent
   * with {@code sample} as the canonical input ({@code PartnerCall.input}) through the candidate
   * route; {@code baseUrl} the partner's sandbox (production; must be in {@code allowedHosts}),
   * else the environment's {@code mw.partners.dry-run.base-url}.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DryRun(String operation, Map<String, Object> sample, String baseUrl) {
    public DryRun {
      sample = sample == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(sample));
    }
  }
}
