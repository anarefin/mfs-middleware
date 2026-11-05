package mfs.mw.admin.api.entity;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One row of {@code admin.content_version} (ticket 39): a version of a USSD menu or a notify
 * template, PENDING with its change request until a checker decides it.
 *
 * @param value the full version as it is broadcast on {@code mw.config.changed.v1}
 */
public record ContentVersion(
    String configKey,
    int version,
    String configType,
    Map<String, Object> value,
    Status status,
    UUID changeRequestId,
    String makerId,
    String checkerId,
    Instant createdAt,
    Instant decidedAt) {

  public static final String USSD_MENU = "USSD_MENU";
  public static final String TEMPLATE = "TEMPLATE";

  public enum Status {
    PENDING,
    PUBLISHED,
    REJECTED,
    EXPIRED
  }

  public ContentVersion {
    value = Collections.unmodifiableMap(new LinkedHashMap<>(value));
  }
}
