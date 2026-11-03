package mfs.mw.service.template.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * This service's own type for {@code mw.config.changed.v1} (spec 20.4; contract owned by
 * mw-admin-api). Every service consumes it to swap its config snapshot. Consumers write their own
 * types (4.3 rule 4) and ignore unknown fields (6.1); {@code ConfigChangedEventSchemaTest} keeps
 * it aligned with the contract.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConfigChangedEvent(
    int schemaVersion,
    String configKey,
    ConfigType configType,
    Change change,
    long version,
    Map<String, Object> value,
    Instant changedAt,
    String changedBy,
    String approvedBy,
    String correlationId) {

  public enum ConfigType {
    PARTNER,
    ROUTE,
    TEMPLATE,
    USSD_MENU,
    LIMIT,
    FEATURE_FLAG,
    ERROR_MAP
  }

  public enum Change {
    UPSERT,
    DELETE
  }
}
