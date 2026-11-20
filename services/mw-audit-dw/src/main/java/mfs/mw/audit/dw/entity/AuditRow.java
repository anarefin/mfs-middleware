package mfs.mw.audit.dw.entity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row of {@code audit.event} (spec 14.3), already masked. {@code month} ({@code yyyy-MM}, UTC
 * month of {@code recordedAt}) names the monthly partition and its hash chain; {@code seq} is the
 * position in that chain, from 1. {@code before} and {@code after} are JSON text in PostgreSQL's
 * {@code jsonb} output form, so the hash can be recomputed from what the table returns. Ticket 130
 * adds the hop facts ({@code hop}, {@code direction}, {@code durationMs}) and the event's {@code
 * audit.payload} rows, all inside the chained hash when present. Never leaves the service layer.
 */
public record AuditRow(
    String month,
    long seq,
    UUID auditId,
    Instant recordedAt,
    Instant at,
    UUID txnId,
    String correlationId,
    String actorType,
    String actorId,
    String action,
    String resource,
    String outcome,
    String service,
    String sourceIp,
    String payloadRef,
    String before,
    String after,
    byte[] prevHash,
    byte[] hash,
    String hop,
    String direction,
    Long durationMs,
    List<PayloadRow> payloads) {

  public AuditRow {
    payloads = payloads == null ? List.of() : List.copyOf(payloads);
  }

  /** A row without hop facts or payloads (every row before ticket 130). */
  public AuditRow(
      String month, long seq, UUID auditId, Instant recordedAt, Instant at, UUID txnId, String correlationId,
      String actorType, String actorId, String action, String resource, String outcome, String service,
      String sourceIp, String payloadRef, String before, String after, byte[] prevHash, byte[] hash) {
    this(month, seq, auditId, recordedAt, at, txnId, correlationId, actorType, actorId, action, resource, outcome,
        service, sourceIp, payloadRef, before, after, prevHash, hash, null, null, null, List.of());
  }

  /** Whether the ticket 130 part of the canonical form applies (hop facts or payloads present). */
  public boolean hasHopFacts() {
    return hop != null || direction != null || durationMs != null || !payloads.isEmpty();
  }

  public AuditRow withAfter(String newAfter) {
    return new AuditRow(
        month, seq, auditId, recordedAt, at, txnId, correlationId, actorType, actorId, action, resource, outcome,
        service, sourceIp, payloadRef, before, newAfter, prevHash, hash, hop, direction, durationMs, payloads);
  }

  public AuditRow withHashes(byte[] newPrevHash, byte[] newHash) {
    return new AuditRow(
        month, seq, auditId, recordedAt, at, txnId, correlationId, actorType, actorId, action, resource, outcome,
        service, sourceIp, payloadRef, before, after, newPrevHash, newHash, hop, direction, durationMs, payloads);
  }

  public AuditRow withHop(String newHop, String newDirection, Long newDurationMs, List<PayloadRow> newPayloads) {
    return new AuditRow(
        month, seq, auditId, recordedAt, at, txnId, correlationId, actorType, actorId, action, resource, outcome,
        service, sourceIp, payloadRef, before, after, prevHash, hash, newHop, newDirection, newDurationMs,
        newPayloads);
  }
}
