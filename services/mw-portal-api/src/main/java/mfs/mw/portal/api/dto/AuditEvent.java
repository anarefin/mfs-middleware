package mfs.mw.portal.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * This service's own type for {@code mw.audit.event.v1} (spec 14.3; contract owned by
 * mw-audit-dw). mw-portal-api emits one per operator action on a bulk batch (and its 72 h expiry),
 * carrying batch states and counts, never the rows; free text is scrubbed (18.3). Since ticket
 * 130b also one per bulk child sent to the orchestrator: the child's CHANNEL_IN hop of the per-hop
 * timeline ({@code hop}, {@code direction} IN) with the masked request as {@code payload}.
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
    String service,
    String hop,
    String direction,
    List<Payload> payload) {

  public AuditEvent {
    before = before == null ? null : Map.copyOf(before);
    after = after == null ? null : Map.copyOf(after);
    payload = payload == null ? null : List.copyOf(payload);
  }

  /** An event without hop facts (a batch action). */
  public AuditEvent(
      int schemaVersion, String auditId, String txnId, String correlationId, Actor actor, String action,
      String resource, Map<String, Object> before, Map<String, Object> after, String outcome, Instant at,
      String service) {
    this(schemaVersion, auditId, txnId, correlationId, actor, action, resource, before, after, outcome, at, service,
        null, null, null);
  }

  /** One hop body: {@code IN} the child request as the middleware took it in; already masked. */
  public record Payload(String direction, String contentType, String body) {}

  /** {@code system}, {@code user} or {@code partner}, and who. */
  public record Actor(String type, String id) {}
}
