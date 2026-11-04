package mfs.mw.audit.dw.entity;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code audit.event} (spec 14.3), already masked. {@code month} ({@code yyyy-MM}, UTC
 * month of {@code recordedAt}) names the monthly partition and its hash chain; {@code seq} is the
 * position in that chain, from 1. {@code before} and {@code after} are JSON text in PostgreSQL's
 * {@code jsonb} output form, so the hash can be recomputed from what the table returns. Never
 * leaves the service layer.
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
    byte[] hash) {

  public AuditRow withAfter(String newAfter) {
    return new AuditRow(
        month, seq, auditId, recordedAt, at, txnId, correlationId, actorType, actorId, action,
        resource, outcome, service, sourceIp, payloadRef, before, newAfter, prevHash, hash);
  }

  public AuditRow withHashes(byte[] newPrevHash, byte[] newHash) {
    return new AuditRow(
        month, seq, auditId, recordedAt, at, txnId, correlationId, actorType, actorId, action,
        resource, outcome, service, sourceIp, payloadRef, before, after, newPrevHash, newHash);
  }
}
