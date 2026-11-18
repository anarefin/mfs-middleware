package mfs.mw.audit.dw.messaging;

import mfs.mw.audit.dw.dto.AuditEvent;
import mfs.mw.audit.dw.service.AuditService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code mw.audit.event.v1} (spec 6.2, 14.3, 19.2, ADR D25): one record, one database
 * transaction, then the record is acknowledged. A record that cannot be read or lacks a required
 * field throws, so the retry topics ({@code config/KafkaConfig}) move it on and finally to the
 * DLQ. Off with {@code mw.audit-dw.consumers.enabled=false} (the test default). The body is not
 * logged.
 */
@Component
public class AuditEventListener {

  public static final String TOPIC = "mw.audit.event.v1";

  private final AuditService service;
  private final JsonMapper json;

  public AuditEventListener(AuditService service, JsonMapper json) {
    this.service = service;
    this.json = json;
  }

  @KafkaListener(
      id = "audit-event",
      topics = TOPIC,
      groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.audit-dw.consumers.enabled:true}")
  public void handle(ConsumerRecord<String, String> record, Acknowledgment ack) {
    service.record(json.readValue(record.value(), AuditEvent.class));
    ack.acknowledge();
  }
}
