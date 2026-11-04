package mfs.mw.adapter.billpay.partner.config;

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
import mfs.mw.adapter.billpay.partner.mapping.JsonataMapping;
import mfs.mw.adapter.billpay.partner.template.PartnerTemplates;
import mfs.mw.adapter.billpay.util.SensitiveType;

/**
 * One partner instance (spec 12.1 layer 1: template + parameters, layer 2: mappings and
 * error-code map). Bound from {@code mw.partners.instances.<id>} (seed) and read from the {@code
 * value} of a {@code mw.config.changed.v1} PARTNER record, with the same field names. Holds no
 * secret, only a {@link Auth#credentialsRef()}. Missing optional fields get their defaults here,
 * so every field is non-null after construction.
 *
 * @param enabled false: every call is refused unsent ({@code NOT_SENT DISABLED})
 * @param template route template, {@code rest-json-request-reply} (default) or {@code
 *     rest-json-async-callback}
 * @param baseUrl partner base URL; operation paths are appended
 * @param connectTimeoutMs TCP connect timeout (default 3000); a failed connect means not sent
 * @param timeoutMs time limiter per call (default 30000, spec 11.1); past it the outcome is UNKNOWN
 * @param readTimeoutMs socket read timeout (default timeoutMs + 2000, so the limiter decides first)
 * @param headers static headers sent on every call
 * @param operations canonical operation (PAY, STATUS_QUERY, CALLBACK, ...) to HTTP call and mappings
 * @param errorMap partner code to canonical code suffix ({@code MW-PTR-<PARTNER>-<suffix>}) and
 *     retryability; an unlisted code is {@code UNMAPPED}, not retryable
 * @param ambiguousCodes partner codes that do not say whether the request was applied (UNKNOWN)
 * @param sensitiveFields JSON field name to masking rule for the audit of raw bodies (free-text
 *     scrubbing applies on top)
 * @param resilience Resilience4j instance name for bulkhead and circuit breaker (default: the
 *     partner instance id)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PartnerConfig(
    Boolean enabled,
    String template,
    URI baseUrl,
    Long connectTimeoutMs,
    Long timeoutMs,
    Long readTimeoutMs,
    Map<String, String> headers,
    Auth auth,
    Map<String, Operation> operations,
    Map<String, ErrorMapping> errorMap,
    Set<String> ambiguousCodes,
    Map<String, SensitiveType> sensitiveFields,
    String resilience) {

  private static final Pattern SUFFIX = Pattern.compile("^[A-Z0-9_]+$");

  /** Prefix of a problem that breaks a security rule (logged as a security event when refused). */
  public static final String SECURITY = "security: ";

  /**
   * An operation path: absolute, plain characters and the {@code {reference}} placeholder only. No
   * {@code @ : ? # \ %}, no {@code ..} segment, no {@code //}: nothing that could move the request
   * to another host, port or user info once appended to the base URL.
   */
  static final Pattern SAFE_PATH = Pattern.compile("^/[A-Za-z0-9._~/{}-]*$");

  public PartnerConfig {
    enabled = enabled == null || enabled;
    template = template == null || template.isBlank() ? PartnerTemplates.REQUEST_REPLY : template;
    connectTimeoutMs = connectTimeoutMs == null ? 3000L : connectTimeoutMs;
    timeoutMs = timeoutMs == null ? 30_000L : timeoutMs;
    readTimeoutMs = readTimeoutMs == null ? timeoutMs + 2000L : readTimeoutMs;
    headers = headers == null ? Map.of() : Map.copyOf(headers);
    auth = auth == null ? new Auth(null, null, null) : auth;
    operations = upperKeys(operations);
    errorMap = errorMap == null ? Map.of() : Map.copyOf(errorMap);
    ambiguousCodes = ambiguousCodes == null ? Set.of() : Set.copyOf(ambiguousCodes);
    sensitiveFields = sensitiveFields == null ? Map.of() : Map.copyOf(sensitiveFields);
  }

  public Duration connectTimeout() {
    return Duration.ofMillis(connectTimeoutMs);
  }

  public Duration timeout() {
    return Duration.ofMillis(timeoutMs);
  }

  public Duration readTimeout() {
    return Duration.ofMillis(readTimeoutMs);
  }

  /** The operation config, or null when this partner does not support it. */
  public Operation operation(String name) {
    return name == null ? null : operations.get(name.toUpperCase(Locale.ROOT));
  }

  /** Error-code map entry for a partner code; {@code UNMAPPED}, not retryable, when unlisted. */
  public ErrorMapping errorMapping(String code) {
    return errorMap.getOrDefault(code, ErrorMapping.UNMAPPED);
  }

  /** Same config with another timeout (and the read timeout following it when it was derived). */
  public PartnerConfig withTimeoutMs(long newTimeoutMs) {
    return new PartnerConfig(
        enabled, template, baseUrl, connectTimeoutMs, newTimeoutMs,
        readTimeoutMs == timeoutMs + 2000L ? null : readTimeoutMs,
        headers, auth, operations, errorMap, ambiguousCodes, sensitiveFields, resilience);
  }

  /** Same config with another base URL (tests point a seed at WireMock with it). */
  public PartnerConfig withBaseUrl(URI newBaseUrl) {
    return new PartnerConfig(
        enabled, template, newBaseUrl, connectTimeoutMs, timeoutMs, readTimeoutMs,
        headers, auth, operations, errorMap, ambiguousCodes, sensitiveFields, resilience);
  }

  /** Everything that stops this config being used; empty when it is complete and compiles. */
  public List<String> problems() {
    List<String> problems = new ArrayList<>();
    if (!PartnerTemplates.ALL.contains(template)) {
      problems.add("unknown template " + template);
    }
    if (baseUrl == null || baseUrl.getScheme() == null || baseUrl.getHost() == null) {
      problems.add("baseUrl must be an absolute URL");
    }
    if (connectTimeoutMs <= 0 || timeoutMs <= 0 || readTimeoutMs <= 0) {
      problems.add("timeouts must be positive");
    }
    if (operations.isEmpty()) {
      problems.add("at least one operation is required");
    }
    operations.forEach((name, op) -> op.problems(name, problems));
    errorMap.forEach(
        (code, m) -> {
          if (m == null || m.suffix() == null || !SUFFIX.matcher(m.suffix()).matches()) {
            problems.add("errorMap." + code + ": suffix must be upper case letters, digits, underscore");
          }
        });
    if (!mfs.mw.adapter.billpay.partner.auth.AuthSchemes.NAMES.contains(auth.scheme())) {
      problems.add("unknown auth scheme " + auth.scheme());
    } else if (!"none".equals(auth.scheme()) && auth.credentialsRef() == null) {
      problems.add("auth scheme " + auth.scheme() + " needs a credentialsRef");
    }
    if (auth.credentialsRef() != null
        && !mfs.mw.adapter.billpay.partner.CredentialRef.NAME.matcher(auth.credentialsRef()).matches()) {
      // the value is not echoed: it may be an attempt to name another secret
      problems.add(
          SECURITY
              + "auth.credentialsRef must be a secret name of this partner (lower-case letters, digits, hyphen)");
    }
    return problems;
  }

  private static Map<String, Operation> upperKeys(Map<String, Operation> in) {
    if (in == null) {
      return Map.of();
    }
    Map<String, Operation> out = new LinkedHashMap<>();
    in.forEach((k, v) -> out.put(k.toUpperCase(Locale.ROOT), v));
    return Map.copyOf(out);
  }

  /**
   * @param scheme {@code none} (default), {@code api-key}, {@code hmac-sha256}
   * @param credentialsRef name of one of this partner's secrets ({@code api-key}), resolved as
   *     {@code mw.partner-secrets.<partner>.<name>}; never the secret, never a property path
   * @param params non-secret scheme parameters (header names, key id, max skew)
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Auth(String scheme, String credentialsRef, Map<String, String> params) {
    public Auth {
      scheme = scheme == null || scheme.isBlank() ? "none" : scheme;
      params = params == null ? Map.of() : Map.copyOf(params);
    }
  }

  /**
   * One canonical operation on the partner's HTTP API.
   *
   * @param method HTTP method
   * @param path path appended to the base URL; {@code {reference}} is replaced by our reference
   * @param definiteStatuses HTTP statuses whose FAILED / NOT_FOUND answers are believed; any other
   *     status turns such an answer into UNKNOWN {@code UNEXPECTED_STATUS} (fail closed: a
   *     rejection releases the hold). SUCCESS and PENDING need a 2xx.
   * @param request JSONata from the canonical call to the request body (none: no body)
   * @param response JSONata from {@code {status, body}} to {@code {outcome, partnerRef, code,
   *     message, reason}}
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Operation(
      String method, String path, List<Integer> definiteStatuses, String request, String response) {

    public Operation {
      method = method == null ? null : method.toUpperCase(Locale.ROOT);
      definiteStatuses = definiteStatuses == null ? List.of() : List.copyOf(definiteStatuses);
    }

    void problems(String name, List<String> problems) {
      if (method == null || method.isBlank()) {
        problems.add("operations." + name + ".method is required");
      }
      if (!safePath(path)) {
        // the value is not echoed: it may be an attempt to reach another host
        problems.add(
            SECURITY
                + "operations." + name + ".path must be an absolute path of letters, digits, . _ ~ - /"
                + " and {reference}, without .. segments or //");
      }
      if (response == null || response.isBlank()) {
        problems.add("operations." + name + ".response mapping is required");
      }
      compiles("operations." + name + ".request", request, problems);
      compiles("operations." + name + ".response", response, problems);
    }

    static boolean safePath(String path) {
      return path != null
          && SAFE_PATH.matcher(path).matches()
          && !path.contains("//")
          && java.util.Arrays.stream(path.split("/")).noneMatch(".."::equals);
    }

    private static void compiles(String field, String source, List<String> problems) {
      if (source == null || source.isBlank()) {
        return;
      }
      try {
        JsonataMapping.compile(source);
      } catch (IllegalArgumentException e) {
        problems.add(field + " does not compile: " + e.getMessage());
      }
    }
  }

  /** Canonical code suffix and retryability of one partner code. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ErrorMapping(String suffix, boolean retryable) {
    public static final ErrorMapping UNMAPPED = new ErrorMapping("UNMAPPED", false);
  }
}
