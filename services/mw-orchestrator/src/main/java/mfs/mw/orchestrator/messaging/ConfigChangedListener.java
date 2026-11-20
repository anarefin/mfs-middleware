package mfs.mw.orchestrator.messaging;

import mfs.mw.orchestrator.dto.ConfigChangedEvent;
import mfs.mw.orchestrator.service.routing.RoutingCatalog;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Consumes {@code mw.config.changed.v1} and applies routing resources (ticket 98, FRS PLT-041). */
@Component
public class ConfigChangedListener {

  private final RoutingCatalog catalog;
  private final JsonMapper json;

  public ConfigChangedListener(RoutingCatalog catalog, JsonMapper json) {
    this.catalog = catalog;
    this.json = json;
  }

  @KafkaListener(
      id = "configChanged",
      topics = "mw.config.changed.v1",
      groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.orchestrator.consumers.enabled:true}")
  public void handle(ConsumerRecord<String, String> record, Acknowledgment ack) {
    if (record.value() == null || record.value().isBlank()) {
      ack.acknowledge();
      return;
    }
    ConfigChangedEvent event = json.readValue(record.value(), ConfigChangedEvent.class);
    if (event.configKey() == null || event.configKey().isBlank() || event.configType() == null) {
      throw new IllegalArgumentException("config change lacks configKey or configType");
    }
    catalog.apply(event);
    ack.acknowledge();
  }
}
