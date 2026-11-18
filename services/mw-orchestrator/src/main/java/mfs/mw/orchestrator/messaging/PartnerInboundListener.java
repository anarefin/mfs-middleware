package mfs.mw.orchestrator.messaging;

import mfs.mw.orchestrator.dto.PartnerInbound;
import mfs.mw.orchestrator.service.PartnerInboundService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code mw.partner.inbound.v1} (ticket 22). A record that cannot be read goes to the DLQ at once; one that
 * lacks its ids throws, so the retry error handler (config/KafkaConfig) parks it on a retry topic and finally the DLQ. The
 * body is never logged.
 */
@Component("partnerInboundListener")
public class PartnerInboundListener {

  private final PartnerInboundService service;
  private final JsonMapper json;

  public PartnerInboundListener(PartnerInboundService service, JsonMapper json) {
    this.service = service;
    this.json = json;
  }

  @KafkaListener(
      id = "partnerInbound",
      topics = "mw.partner.inbound.v1",
      groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.orchestrator.consumers.enabled:true}")
  public void handle(ConsumerRecord<String, String> record, Acknowledgment ack) {
    PartnerInbound inbound = json.readValue(record.value(), PartnerInbound.class);
    if (inbound.inboundId() == null || inbound.inboundId().isBlank() || inbound.txnId() == null
        || inbound.payload() == null) {
      throw new IllegalArgumentException("partner inbound message lacks inboundId, txnId or payload");
    }
    service.handle(inbound);
    ack.acknowledge();
  }
}
