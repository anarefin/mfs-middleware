package mfs.mw.admin.api.service;

import java.time.Clock;
import java.util.UUID;
import mfs.mw.admin.api.dto.AuditEvent;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.mapper.RevealAuditMapper;
import mfs.mw.admin.api.messaging.EventMetadata;
import mfs.mw.admin.api.messaging.OutboxWriter;
import mfs.mw.admin.api.security.AdminRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the audit event of an MSISDN reveal (spec 15.4) to the outbox and commits it before the
 * unmasked view is returned (spec 6.4): if it cannot be written, the operator gets an error, never
 * an unaudited reveal. Key: the txnId, so one transaction's trail keeps its order on one partition.
 */
@Component
public class RevealAudit {

  static final String TOPIC = "mw.audit.event.v1";
  private static final Logger log = LoggerFactory.getLogger(RevealAudit.class);

  private final OutboxWriter outbox;
  private final Clock clock;

  public RevealAudit(OutboxWriter outbox, Clock clock) {
    this.outbox = outbox;
    this.clock = clock;
  }

  @Transactional
  public void record(Operator operator, AdminRole grantedBy, UUID txnId) {
    AuditEvent e = RevealAuditMapper.event(operator, grantedBy, txnId, MDC.get("correlationId"), clock.instant());
    outbox.append(TOPIC, txnId.toString(), new EventMetadata(e.correlationId(), e.txnId(), e.auditId(), 1), e);
    log.atInfo()
        .addKeyValue("event", "admin.txn.msisdn.revealed")
        .addKeyValue("txnId", txnId.toString())
        .addKeyValue("role", grantedBy.id())
        .log("unmasked MSISDN shown to an operator (audited)");
  }
}
