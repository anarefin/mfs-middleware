package mfs.mw.audit.dw.messaging;

import mfs.mw.audit.dw.dto.ReconExceptionEvent;
import mfs.mw.audit.dw.service.ReconExceptionAudit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code mw.recon.exception.v1} (owner mw-recon, ticket 127b) into the audit trail: one record, one audit
 * row, then the record is acknowledged. A record that cannot be read or lacks a needed field throws and moves
 * through the retry topics ({@code mw.recon.exception.v1.mw-audit-dw.retry.1..3}) to the DLQ. Off with {@code
 * mw.audit-dw.consumers.enabled=false} (the test default). The body is not logged.
 */
@Component
public class ReconExceptionListener {

  public static final String TOPIC = "mw.recon.exception.v1";

  private final ReconExceptionAudit service;
  private final JsonMapper json;

  public ReconExceptionListener(ReconExceptionAudit service, JsonMapper json) {
    this.service = service;
    this.json = json;
  }

  @KafkaListener(
      id = "recon-exception",
      topics = TOPIC,
      groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.audit-dw.consumers.enabled:true}")
  public void handle(ConsumerRecord<String, String> record, Acknowledgment ack) {
    service.record(json.readValue(record.value(), ReconExceptionEvent.class));
    ack.acknowledge();
  }
}
