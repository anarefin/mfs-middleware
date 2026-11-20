package mfs.mw.orchestrator.service;

import java.time.Clock;
import mfs.mw.orchestrator.dto.AuditEvent;
import mfs.mw.orchestrator.dto.CoreCommand;
import mfs.mw.orchestrator.dto.CoreReply;
import mfs.mw.orchestrator.mapper.AuditMapper;
import mfs.mw.orchestrator.messaging.EventMetadata;
import mfs.mw.orchestrator.messaging.OutboxWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audits one core REST call (ticket 130b, FRS AUD-01, AUD-04): a {@code core.call.completed} event
 * with hop CORE_CALL on {@code mw.audit.event.v1}, through the outbox in a transaction of its own
 * (the call ran after the step's transaction committed), so the transaction's timeline shows every
 * core call with its latency, also one whose answer the saga then ignores or never got.
 */
@Service
public class CoreCallAuditService {

  private final AuditMapper audits;
  private final OutboxWriter outbox;
  private final Clock clock;

  public CoreCallAuditService(AuditMapper audits, OutboxWriter outbox, Clock clock) {
    this.audits = audits;
    this.outbox = outbox;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(CoreCommand command, CoreReply reply, long durationMs) {
    AuditEvent e = audits.coreCall(command, reply, clock.instant(), durationMs);
    outbox.append(
        SagaEngine.AUDIT_TOPIC, e.txnId(), new EventMetadata(e.correlationId(), e.txnId(), e.auditId(), 1), e);
  }
}
