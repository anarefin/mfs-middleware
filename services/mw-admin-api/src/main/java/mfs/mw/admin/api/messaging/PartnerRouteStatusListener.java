package mfs.mw.admin.api.messaging;

import mfs.mw.admin.api.dto.PartnerRouteStatusMessage;
import mfs.mw.admin.api.service.PartnerRouteStatusService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code mw.partner.route.status.v1} (group {@code mw-admin-api}, retry topics and DLQ,
 * ADR D25, {@code config/KafkaConfig}; ticket 83): the adapters' LIVE / REJECTED report per
 * partner route version, handed to {@link PartnerRouteStatusService}, then acknowledged. A record
 * that cannot be read throws and is parked. Short database work only on the consumer thread. Off
 * with {@code mw.partner-routes.status-consumer.enabled=false} (the test default).
 */
@Component
public class PartnerRouteStatusListener {

  public static final String TOPIC = "mw.partner.route.status.v1";

  private final PartnerRouteStatusService service;
  private final JsonMapper json;

  public PartnerRouteStatusListener(PartnerRouteStatusService service, JsonMapper json) {
    this.service = service;
    this.json = json;
  }

  @KafkaListener(
      id = "partner-route-status",
      topics = TOPIC,
      groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.partner-routes.status-consumer.enabled:true}")
  public void handle(ConsumerRecord<String, String> record, Acknowledgment ack) {
    service.apply(json.readValue(record.value(), PartnerRouteStatusMessage.class));
    ack.acknowledge();
  }
}
