package mfs.mw.app.gateway.messaging;

import java.util.Set;
import mfs.mw.app.gateway.dto.StatusMessage;
import mfs.mw.app.gateway.service.StatusProjectionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared consumer of {@code mw.txn.status.v1} (consumer group {@code mw-app-gateway}, retry topics
 * and DLQ from {@code config/KafkaConfig}; spec 6.2, 19.2, ADR D27): keeps the latest status of
 * every app-channel transaction for {@code GET}. A record that cannot be read throws, so the error
 * handler parks it on a retry topic and finally the DLQ (a schema failure goes to the DLQ at once).
 * Off when {@code mw.app-gateway.consumers.enabled=false}.
 */
@Component
public class StatusProjectionListener {

  /** Channels served by this service (spec 5.1); other channels' statuses are skipped. */
  static final Set<String> APP_CHANNELS =
      Set.of("CUSTOMER_APP", "AGENT_APP", "MERCHANT_APP", "DISTRIBUTOR_APP", "DSO_APP", "PARTNER_APP");

  private final StatusProjectionService projection;
  private final JsonMapper json;

  public StatusProjectionListener(StatusProjectionService projection, JsonMapper json) {
    this.projection = projection;
    this.json = json;
  }

  @KafkaListener(
      id = "status-projection",
      topics = "${mw.app-gateway.status-topic}",
      groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.app-gateway.consumers.enabled:true}")
  public void onStatus(ConsumerRecord<String, String> record, Acknowledgment ack) {
    StatusMessage status = json.readValue(record.value(), StatusMessage.class);
    if (APP_CHANNELS.contains(status.channel())) {
      projection.apply(status);
    }
    ack.acknowledge();
  }
}
