package mfs.mw.adapter.bank.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * This service's own type for {@code mw.audit.event.v1} (spec 14.3, 17; contract owned by
 * mw-audit-dw). The adapter emits one per partner call ({@code partner.call.completed}); {@code
 * after} carries the call's facts and its request and response bodies, masked before they get here
 * (18.3). Raw bodies are never inlined.
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
