package mfs.mw.portal.api.service;

import java.time.Clock;
import mfs.mw.portal.api.dto.AuditEvent;
import mfs.mw.portal.api.entity.Batch;
import mfs.mw.portal.api.entity.BatchStatus;
import mfs.mw.portal.api.mapper.BatchAuditMapper;
import mfs.mw.portal.api.messaging.EventMetadata;
import mfs.mw.portal.api.messaging.OutboxWriter;
import mfs.mw.portal.api.util.BulkIds;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Writes the audit event of a bulk batch action (M3 gate decision) to the outbox, inside the
 * caller's transaction, so it is published exactly when the action commits (spec 6.4). Key: the
 * batch's parent txnId (the contract allows "txnId or actor id"), derived from the batch id so it
 * exists from the upload on. Keying by the actor put the maker's upload and the checker's decision
 * on different partitions, and mw-audit-dw could chain them out of order.
 */
@Component
public class BatchAudit {

  static final String TOPIC = "mw.audit.event.v1";

  private final OutboxWriter outbox;
  private final Clock clock;

  public BatchAudit(OutboxWriter outbox, Clock clock) {
    this.outbox = outbox;
    this.clock = clock;
  }

  /** @param operator the operator's username, or null for the service itself */
  public void record(
      Batch batch, String action, String operator, BatchStatus from, BatchStatus to, String reason) {
    AuditEvent e =
        BatchAuditMapper.event(batch, action, operator, from, to, reason, MDC.get("correlationId"), clock.instant());
    String txnId = e.txnId() == null ? batch.batchId().toString() : e.txnId();
    String key = BulkIds.parentTxnId(batch.batchId()).toString();
    outbox.append(TOPIC, key, new EventMetadata(e.correlationId(), txnId, e.auditId(), 1), e);
  }
}
