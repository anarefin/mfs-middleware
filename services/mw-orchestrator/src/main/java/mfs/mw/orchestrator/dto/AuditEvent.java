package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * This service's own type for {@code mw.audit.event.v1} (spec 14.3; contract owned by
 * mw-audit-dw). The orchestrator emits one per saga state change, carrying only states and error
 * codes, never parties, amounts or the PIN, so there is nothing personal to mask (18.3). Since
 * ticket 130b it also emits one per core REST call ({@code core.call.completed}) with the hop facts
 * of the per-hop timeline ({@code hop} CORE_CALL, {@code direction} OUT, {@code durationMs}) and
 * the call's bodies as {@code payload}, masked before they get here.
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
    String service,
    String hop,
    String direction,
    Long durationMs,
    List<Payload> payload) {

  public AuditEvent {
    before = before == null ? null : Map.copyOf(before);
    after = after == null ? null : Map.copyOf(after);
    payload = payload == null ? null : List.copyOf(payload);
  }

  /** An event without hop facts (state changes, operator actions, callbacks). */
  public AuditEvent(
      int schemaVersion, String auditId, String txnId, String correlationId, Actor actor, String action,
      String resource, Map<String, String> before, Map<String, String> after, String outcome, Instant at,
      String service) {
    this(schemaVersion, auditId, txnId, correlationId, actor, action, resource, before, after, outcome, at, service,
        null, null, null, null);
  }

  /**
   * One hop body (ticket 130b, FRS AUD-01): {@code OUT} sent by the middleware (the core request),
   * {@code IN} received (the core's answer); already masked.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Payload(String direction, String contentType, String body) {}

  /** {@code system}, {@code user} or {@code partner}, and who. */
  public record Actor(String type, String id) {}
}
