package mfs.mw.app.gateway.messaging;

import java.util.Map;
import mfs.mw.app.gateway.dto.ConfigChangedEvent;
import mfs.mw.app.gateway.service.BillerCatalog;
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
 * Consumes {@code mw.config.changed.v1} BILLER records into {@link BillerCatalog} (ticket 113b,
 * copied from mw-adapter-billpay): a stable group per instance ({@code <service>-billers-<HOSTNAME>}),
 * seek to the beginning on every assignment, never commits, so the compacted topic rebuilds the
 * snapshot on each start; never throws (a bad record is logged and skipped; the topic is in {@code
 * mw.kafka.retry.exclude-topics}, so it has no retry topics here). Never blocks: an in-memory put.
 * {@code mw.biller-catalog.consumer.enabled=false} keeps it from starting (tests).
 */
@Component
public class BillerCatalogKafkaListener extends AbstractConsumerSeekAware {

  public static final String TOPIC = "mw.config.changed.v1";

  private static final Logger log = LoggerFactory.getLogger(BillerCatalogKafkaListener.class);

  private final BillerCatalog catalog;
  private final JsonMapper json;

  public BillerCatalogKafkaListener(BillerCatalog catalog, JsonMapper json) {
    this.catalog = catalog;
    this.json = json;
  }

  @KafkaListener(
      id = "biller-catalog",
      topics = TOPIC,
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
  public void onPartitionsAssigned(
      Map<TopicPartition, Long> assignments, ConsumerSeekAware.ConsumerSeekCallback callback) {
    super.onPartitionsAssigned(assignments, callback);
    callback.seekToBeginning(assignments.keySet());
  }
}
