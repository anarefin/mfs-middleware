package mfs.mw.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * This service's own type for {@code mw.audit.event.v1} (spec 14.3, 18.7; contract owned by
 * mw-audit-dw). Ticket 70: every PIN lock, unlock, change and reset and every wallet lock command.
 * Never PIN material; the wallet is masked in {@code resource}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditEvent(
    int schemaVersion,
    String auditId,
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

  /** {@code system} or {@code user}, and who. */
  public record Actor(String type, String id) {}
}
