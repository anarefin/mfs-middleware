package mfs.mw.audit.dw.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * One stored audit event as the search API returns it (ticket 35, contract {@code
 * audit-query.v1.openapi.yaml}). Values are as stored, i.e. already masked (spec 18.3).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditEntry(
    String auditId,
    String month,
    long seq,
    Instant recordedAt,
    Instant at,
    String txnId,
    String correlationId,
    AuditEvent.Actor actor,
    String action,
    String resource,
    String outcome,
    String service,
    String sourceIp,
    String payloadRef,
    JsonNode before,
    JsonNode after,
    String hop,
    String direction,
    Long durationMs) {

  /** An entry without hop facts (ticket 35 shape). */
  public AuditEntry(
      String auditId, String month, long seq, Instant recordedAt, Instant at, String txnId, String correlationId,
      AuditEvent.Actor actor, String action, String resource, String outcome, String service, String sourceIp,
      String payloadRef, JsonNode before, JsonNode after) {
    this(auditId, month, seq, recordedAt, at, txnId, correlationId, actor, action, resource, outcome, service,
        sourceIp, payloadRef, before, after, null, null, null);
  }

  /**
   * One page of results, oldest first, with the status of the signed daily anchors (ticket 74) for
   * the admin console's audit view.
   */
  public record Page(List<AuditEntry> items, int limit, int offset, AnchorStatus anchor) {}
}
