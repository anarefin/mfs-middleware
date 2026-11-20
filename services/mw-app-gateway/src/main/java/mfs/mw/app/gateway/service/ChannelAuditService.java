package mfs.mw.app.gateway.service;

import java.time.Clock;
import mfs.mw.app.gateway.dto.AuditEvent;
import mfs.mw.app.gateway.mapper.ChannelAuditMapper;
import mfs.mw.app.gateway.messaging.EventMetadata;
import mfs.mw.app.gateway.messaging.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the CHANNEL_IN audit hop of a served transaction request (ticket 130b, FRS AUD-01, AUD-04)
 * on {@code mw.audit.event.v1} through the outbox, in a transaction of its own. A failure is logged
 * and never fails the customer's request.
 */
@Service
public class ChannelAuditService {

  public static final String AUDIT_TOPIC = "mw.audit.event.v1";

  private static final Logger log = LoggerFactory.getLogger(ChannelAuditService.class);

  private final OutboxWriter outbox;
  private final TransactionTemplate tx;
  private final Clock clock;

  public ChannelAuditService(OutboxWriter outbox, TransactionTemplate tx, Clock clock) {
    this.outbox = outbox;
    this.tx = tx;
    this.clock = clock;
  }

  public void served(ChannelAuditMapper.Served served, long durationMs) {
    try {
      AuditEvent e = ChannelAuditMapper.channelIn(served, clock.instant(), durationMs);
      tx.executeWithoutResult(s -> outbox.append(
          AUDIT_TOPIC, e.txnId(), new EventMetadata(e.correlationId(), e.txnId(), e.auditId(), 1), e));
    } catch (RuntimeException e) {
      log.atWarn()
          .addKeyValue("event", "channel.audit.failed")
          .addKeyValue("txnId", served.txnId())
          .addKeyValue("errorClass", e.getClass().getSimpleName())
          .log("channel request audit not written");
    }
  }
}
