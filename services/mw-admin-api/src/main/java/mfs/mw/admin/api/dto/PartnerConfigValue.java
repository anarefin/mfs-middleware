package mfs.mw.admin.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The full config of one partner instance (ticket 38, spec 12.1), in the field names of the
 * adapters' partner framework ({@code partner/config/PartnerConfig} in mw-adapter-telco, -billpay,
 * -bank): published unchanged as the {@code value} of a {@code mw.config.changed.v1} PARTNER record.
 * Holds no secret: {@code auth.credentialsRef} names one of the partner's secrets in OpenBao.
 * Missing optional fields are left out (the adapters apply their defaults).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PartnerConfigValue(
    Boolean enabled,
    String template,
    String baseUrl,
    Long connectTimeoutMs,
    Long timeoutMs,
    Long readTimeoutMs,
    Map<String, String> headers,
    Auth auth,
    Map<String, Operation> operations,
    Map<String, ErrorMapping> errorMap,
    List<String> ambiguousCodes,
    Map<String, String> sensitiveFields,
    String resilience) {

  public PartnerConfigValue {
    headers = copy(headers);
    operations = copy(operations);
    errorMap = copy(errorMap);
    ambiguousCodes = ambiguousCodes == null ? null : List.copyOf(ambiguousCodes);
    sensitiveFields = copy(sensitiveFields);
  }

  /** Whether calls are allowed (the adapters' default when unset: true). */
  public boolean isEnabled() {
    return enabled == null || enabled;
  }

  public PartnerConfigValue withEnabled(boolean value) {
    return new PartnerConfigValue(
        value, template, baseUrl, connectTimeoutMs, timeoutMs, readTimeoutMs, headers, auth, operations, errorMap,
        ambiguousCodes, sensitiveFields, resilience);
  }

  /** The timeouts given replace the held ones; null leaves one as it is. */
  public PartnerConfigValue withTimeouts(Long connect, Long timeout, Long read) {
    return new PartnerConfigValue(
        enabled, template, baseUrl, connect != null ? connect : connectTimeoutMs, timeout != null ? timeout : timeoutMs,
        read != null ? read : readTimeoutMs, headers, auth, operations, errorMap, ambiguousCodes, sensitiveFields,
        resilience);
  }

  private static <V> Map<String, V> copy(Map<String, V> in) {
    return in == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(in));
  }

  /** Auth scheme ({@code none}, {@code api-key}, {@code hmac-sha256}), secret name, non-secret params. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Auth(String scheme, String credentialsRef, Map<String, String> params) {
    public Auth {
      params = copy(params);
    }
  }

  /** One canonical operation: HTTP method and path, definite statuses, JSONata mappings. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Operation(
      String method, String path, List<Integer> definiteStatuses, String request, String response) {
    public Operation {
      definiteStatuses = definiteStatuses == null ? null : List.copyOf(definiteStatuses);
    }
  }

  /** Canonical code suffix ({@code MW-PTR-<PARTNER>-<suffix>}) and retryability of one partner code. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ErrorMapping(String suffix, boolean retryable) {}
}
