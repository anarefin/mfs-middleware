package mfs.mw.admin.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * This service's own type for {@code mw.audit.event.v1} (spec 14.3; contract owned by
 * mw-audit-dw). mw-admin-api emits one per operator action it must account for; in ticket 36 that
 * is every reveal of an unmasked MSISDN (spec 15.4), which names what was shown, never the value.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditEvent(
    int schemaVersion,
    String auditId,
    String txnId,
    String correlationId,
    Actor actor,
    String action,
    String resource,
    Map<String, Object> before,
    Map<String, Object> after,
    String outcome,
    Instant at,
    String service) {

  public AuditEvent {
    before = before == null ? null : Map.copyOf(before);
    after = after == null ? null : Map.copyOf(after);
  }

  /** {@code system}, {@code user} or {@code partner}, and who. */
  public record Actor(String type, String id) {}
}
