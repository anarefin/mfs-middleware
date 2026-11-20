package mfs.mw.orchestrator.messaging;

import mfs.mw.orchestrator.dto.PartnerRouteStatus;
import mfs.mw.orchestrator.service.routing.RoutingCatalog;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code mw.partner.route.status.v1} for partner health (ticket 97, used by ticket 98).
 * A report without {@code health} is a route-load report and changes nothing.
 */
@Component
public class PartnerRouteStatusListener {

  private final RoutingCatalog catalog;
  private final JsonMapper json;

  public PartnerRouteStatusListener(RoutingCatalog catalog, JsonMapper json) {
    this.catalog = catalog;
    this.json = json;
  }

  @KafkaListener(
      id = "partnerRouteStatus",
      topics = "mw.partner.route.status.v1",
      groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.orchestrator.consumers.enabled:true}")
  public void handle(ConsumerRecord<String, String> record, Acknowledgment ack) {
    if (record.value() == null || record.value().isBlank()) {
      ack.acknowledge();
      return;
    }
    PartnerRouteStatus status = json.readValue(record.value(), PartnerRouteStatus.class);
    if (status.partnerId() == null || status.partnerId().isBlank()) {
      throw new IllegalArgumentException("partner route status lacks partnerId");
    }
    if (status.health() != null && !status.health().isBlank()) {
      catalog.noteHealth(status.partnerId(), status.health());
    }
    ack.acknowledge();
  }
}
