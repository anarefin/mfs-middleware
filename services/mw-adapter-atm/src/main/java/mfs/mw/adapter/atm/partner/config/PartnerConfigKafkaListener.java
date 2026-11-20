package mfs.mw.adapter.atm.partner.config;

import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.AbstractConsumerSeekAware;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code mw.config.changed.v1} (compacted, keyed by configKey) into the {@link
 * PartnerConfigStore} (Spring Kafka, ADR D27). Every instance must see every record: its consumer
 * group is stable per instance ({@code <service>-config-<HOSTNAME>}, so a restart never leaves an
 * orphaned group behind, where the former Camel route started a new random group each time), it
 * seeks to the beginning on every assignment and never commits, so the compacted topic replays the
 * latest value of every key on each start and rebuilds the snapshot (ticket 38). No retry topics
 * ({@code mw.kafka.retry.exclude-topics}): {@link PartnerConfigListener} never throws. On by
 * default; {@code mw.partners.config-consumer.enabled=false} keeps it from starting (tests).
 */
@Component
public class PartnerConfigKafkaListener extends AbstractConsumerSeekAware {

  public static final String TOPIC = "mw.config.changed.v1";

  private final PartnerConfigListener listener;

  public PartnerConfigKafkaListener(PartnerConfigListener listener) {
    this.listener = listener;
  }

  @KafkaListener(
      id = "partner-config",
      topics = TOPIC,
      groupId = "${spring.application.name}-config-${HOSTNAME:local}",
      autoStartup = "${mw.partners.config-consumer.enabled:true}")
  public void onRecord(ConsumerRecord<String, String> record) {
    listener.onRecord(record.value());
  }

  @Override
  public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekAware.ConsumerSeekCallback callback) {
    super.onPartitionsAssigned(assignments, callback);
    callback.seekToBeginning(assignments.keySet());
  }
}
