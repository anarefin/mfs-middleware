package mfs.mw.adapter.billpay.messaging;

import java.util.Map;
import mfs.mw.adapter.billpay.dto.ConfigChangedEvent;
import mfs.mw.adapter.billpay.service.BillerCatalog;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.AbstractConsumerSeekAware;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code mw.config.changed.v1} BILLER records into {@link BillerCatalog} (ticket 113),
 * beside the partner engine's own listener (whose {@code partner/} tree stays identical in the
 * three adapters). Like it: a stable group per instance ({@code <service>-billers-<HOSTNAME>}),
 * seek to the beginning on every assignment, never commits, so the compacted topic rebuilds the
 * snapshot on each start; never throws (a bad record is logged and skipped; the topic has no retry
 * topics here). {@code mw.biller-catalog.consumer.enabled=false} keeps it from starting (tests).
 */
@Component
public class BillerCatalogKafkaListener extends AbstractConsumerSeekAware {

  private static final Logger log = LoggerFactory.getLogger(BillerCatalogKafkaListener.class);

  private final BillerCatalog catalog;
  private final JsonMapper json;

  public BillerCatalogKafkaListener(BillerCatalog catalog, JsonMapper json) {
    this.catalog = catalog;
    this.json = json;
  }

  @KafkaListener(
      id = "biller-catalog",
      topics = "mw.config.changed.v1",
      groupId = "${spring.application.name}-billers-${HOSTNAME:local}",
      autoStartup = "${mw.biller-catalog.consumer.enabled:true}")
  public void onRecord(ConsumerRecord<String, String> record) {
    String body = record.value();
    if (body == null || body.isBlank() || record.key() == null || !record.key().startsWith("biller/")) {
      return;
    }
    try {
      catalog.apply(json.readValue(body, ConfigChangedEvent.class));
    } catch (RuntimeException e) {
      log.atWarn()
          .addKeyValue("event", "config.unreadable")
          .addKeyValue("errorClass", e.getClass().getName())
          .log("biller record skipped");
    }
  }

  @Override
  public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekAware.ConsumerSeekCallback callback) {
    super.onPartitionsAssigned(assignments, callback);
    callback.seekToBeginning(assignments.keySet());
  }
}
