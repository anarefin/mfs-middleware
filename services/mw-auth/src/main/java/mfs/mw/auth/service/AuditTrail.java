package mfs.mw.auth.service;

import java.time.Clock;
import java.util.Map;
import mfs.mw.auth.config.PinProperties;
import mfs.mw.auth.dto.AuditEvent;
import mfs.mw.auth.mapper.AuditEventMapper;
import mfs.mw.auth.messaging.EventMetadata;
import mfs.mw.auth.messaging.OutboxWriter;
import org.springframework.stereotype.Service;

/**
 * Writes an audit event ({@code mw.audit.event.v1}) to the outbox, so it commits with the change it
 * describes (spec 6.4, 18.7). Call inside the change's transaction.
 */
@Service
public class AuditTrail {

  private final OutboxWriter outbox;
  private final AuditEventMapper mapper;
  private final PinProperties props;
  private final Clock clock;

  public AuditTrail(OutboxWriter outbox, AuditEventMapper mapper, PinProperties props, Clock clock) {
    this.outbox = outbox;
    this.mapper = mapper;
    this.props = props;
    this.clock = clock;
  }

  public void record(
      String action, String walletId, AuditEvent.Actor actor, String outcome, Map<String, Object> before,
      Map<String, Object> after) {
    String correlationId = DeviceOtpService.correlationId();
    AuditEvent e = mapper.event(action, walletId, actor, outcome, before, after, correlationId, clock.instant());
    outbox.append(props.auditTopic(), e.resource(), new EventMetadata(correlationId, e.auditId(), e.auditId(), 1), e);
  }
}
