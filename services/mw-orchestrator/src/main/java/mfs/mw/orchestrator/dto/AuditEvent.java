package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * This service's own type for {@code mw.audit.event.v1} (spec 14.3; contract owned by
 * mw-audit-dw). The orchestrator emits one per saga state change, carrying only states and error
 * codes, never parties, amounts or the PIN, so there is nothing personal to mask (18.3).
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
    Map<String, String> before,
    Map<String, String> after,
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
