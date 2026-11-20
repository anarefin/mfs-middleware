package mfs.mw.adapter.atm.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * This service's own type for {@code mw.audit.event.v1} (spec 14.3, 17; contract owned by
 * mw-audit-dw). The adapter emits one per partner call ({@code partner.call.completed}); {@code
 * after} carries the call's facts and its request and response bodies, masked before they get here
 * (18.3). Raw bodies are never inlined: since ticket 97 {@code payloadRef} names the full masked
 * bodies in the adapter's {@code partner_payload} table ({@code partner-payload/<uuid>}). Since
 * ticket 130 it also carries the hop facts of the per-hop timeline ({@code hop} PARTNER_CALL,
 * {@code direction} OUT, {@code durationMs}) and the masked bodies as {@code payload}.
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
    String payloadRef,
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

  /**
   * One hop body (ticket 130, FRS AUD-01): {@code OUT} sent by the middleware (the partner request),
   * {@code IN} received (the partner's answer); already masked, an excerpt of the full masked body
   * behind {@code payloadRef} when there is one.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Payload(String direction, String contentType, String body, String payloadRef) {}

  /** {@code system}, {@code user} or {@code partner}, and who. */
  public record Actor(String type, String id) {}
}
