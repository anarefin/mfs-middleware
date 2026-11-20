package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * This service's own type for {@code mw.audit.event.v1} (spec 14.3; contract owned by
 * mw-audit-dw). The channel emits one per transaction request it served (ticket 130b, FRS AUD-01,
 * AUD-04): action {@code channel.request.completed}, hop CHANNEL_IN, direction IN, the request's
 * latency and its request and answer, masked before they get here (18.3).
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
    Map<String, String> after,
    String outcome,
    Instant at,
    String service,
    String hop,
    String direction,
    Long durationMs,
    List<Payload> payload) {

  public AuditEvent {
    after = after == null ? null : Map.copyOf(after);
    payload = payload == null ? null : List.copyOf(payload);
  }

  /** One hop body: {@code IN} the request received, {@code OUT} the answer sent; already masked. */
  public record Payload(String direction, String contentType, String body) {}

  /** {@code system}, {@code user} or {@code partner}, and who. */
  public record Actor(String type, String id) {}
}
