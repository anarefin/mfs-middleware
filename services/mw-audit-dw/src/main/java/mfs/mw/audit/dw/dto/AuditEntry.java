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
    JsonNode after) {

  /** One page of results, oldest first. */
  public record Page(List<AuditEntry> items, int limit, int offset) {}
}
