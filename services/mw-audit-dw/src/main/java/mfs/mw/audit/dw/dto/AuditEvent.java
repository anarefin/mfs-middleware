package mfs.mw.audit.dw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;
import mfs.mw.audit.dw.util.Sensitive;
import mfs.mw.audit.dw.util.SensitiveType;
import tools.jackson.databind.JsonNode;

/**
 * {@code mw.audit.event.v1} (spec 14.3; contract owned by this service, produced by every
 * service). Producers mask before publishing (18.3); this service masks again before storing.
 * Unknown fields are ignored (6.1). {@code before}/{@code after} stay JSON trees.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditEvent(
    int schemaVersion,
    UUID auditId,
    String txnId,
    String correlationId,
    Actor actor,
    String action,
    String resource,
    JsonNode before,
    JsonNode after,
    String payloadRef,
    String outcome,
    Instant at,
    String sourceIp,
    String service,
    String hop,
    String direction,
    Long durationMs,
    java.util.List<Payload> payload) {

  /**
   * An event without the hop facts of ticket 130 (every producer before it, and this service's own
   * operator-action records).
   */
  public AuditEvent(
      int schemaVersion, UUID auditId, String txnId, String correlationId, Actor actor, String action,
      String resource, JsonNode before, JsonNode after, String payloadRef, String outcome, Instant at,
      String sourceIp, String service) {
    this(schemaVersion, auditId, txnId, correlationId, actor, action, resource, before, after, payloadRef,
        outcome, at, sourceIp, service, null, null, null, null);
  }

  /**
   * One hop body (ticket 130): {@code IN} received by the middleware, {@code OUT} sent; masked by
   * the producer, scrubbed again here. {@code body} may be an excerpt of the full masked body
   * behind {@code payloadRef}.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Payload(String direction, String contentType, String body, String payloadRef) {}

  /** Who did it: {@code system} (a service), {@code user} (operator or customer), {@code partner}. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Actor(String type, @Sensitive(SensitiveType.NAME) String id) {}
}
