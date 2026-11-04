package mfs.mw.adapter.telco.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import mfs.mw.adapter.telco.util.Sensitive;
import mfs.mw.adapter.telco.util.SensitiveType;

/**
 * This service's own type for {@code mw.config.changed.v1} (spec 20.4; contract owned by
 * mw-admin-api). Every service consumes it to swap its config snapshot. Consumers write their own
 * types (4.3 rule 4) and ignore unknown fields (6.1); {@code ConfigChangedEventSchemaTest} keeps
 * it aligned with the contract. {@code changedBy}/{@code approvedBy} are staff user names, often
 * built from personal names, so they are masked as names in logs (18.3).
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
    @Sensitive(SensitiveType.NAME) String changedBy,
    @Sensitive(SensitiveType.NAME) String approvedBy,
    String correlationId) {

  /** {@code value} is copied and read-only (JSON values may be null, so not Map.copyOf). */
  public ConfigChangedEvent {
    value = value == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(value));
  }

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
