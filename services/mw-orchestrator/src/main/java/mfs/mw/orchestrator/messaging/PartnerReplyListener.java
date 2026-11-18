package mfs.mw.orchestrator.messaging;

import mfs.mw.orchestrator.dto.PartnerReply;
import mfs.mw.orchestrator.service.PartnerReplyService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code mw.partner.reply.v1}. A record that cannot be read goes to the DLQ at once; one that lacks its
 * ids throws, so the retry error handler (config/KafkaConfig) parks it on a retry topic and finally the DLQ. The body is never logged.
 */
@Component("partnerReplyListener")
public class PartnerReplyListener {

  private final PartnerReplyService service;
  private final JsonMapper json;

  public PartnerReplyListener(PartnerReplyService service, JsonMapper json) {
    this.service = service;
    this.json = json;
  }

  @KafkaListener(
      id = "partnerReply",
      topics = "mw.partner.reply.v1",
      groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.orchestrator.consumers.enabled:true}")
  public void handle(ConsumerRecord<String, String> record, Acknowledgment ack) {
    PartnerReply reply = json.readValue(record.value(), PartnerReply.class);
    if (reply.requestId() == null || reply.requestId().isBlank() || reply.txnId() == null || reply.payload() == null) {
      throw new IllegalArgumentException("partner reply lacks requestId, txnId or payload");
    }
    service.handle(reply);
    ack.acknowledge();
  }
}
