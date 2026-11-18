package mfs.mw.ussd.messaging;

import mfs.mw.ussd.dto.ConfigChangedEvent;
import mfs.mw.ussd.service.MenuPublisher;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumer of {@code mw.config.changed.v1} (compacted; spec 20.4, ticket 39; consumer group {@code
 * mw-ussd}, earliest offset, so a service started on an empty group reads the latest record of
 * every key; retry topics and DLQ from {@code config/KafkaConfig}, ADR D27): reads the record and
 * hands it to {@link MenuPublisher}, which stores a USSD menu version in one short transaction and
 * ignores every other config type. A tombstone (empty body, compaction) is skipped. A record that
 * cannot be read, or a store that fails, throws: the error handler parks it on a retry topic and
 * finally the DLQ (a schema failure goes to the DLQ at once). The body is not logged. Off when
 * {@code mw.ussd.consumers.enabled=false}.
 */
@Component
public class ConfigChangedListener {

  public static final String TOPIC = "mw.config.changed.v1";

  private final MenuPublisher menus;
  private final JsonMapper json;

  public ConfigChangedListener(MenuPublisher menus, JsonMapper json) {
    this.menus = menus;
    this.json = json;
  }

  @KafkaListener(
      id = "config-changed",
      topics = TOPIC,
      groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.ussd.consumers.enabled:true}")
  public void onConfigChanged(ConsumerRecord<String, String> record, Acknowledgment ack) {
    apply(record.value());
    ack.acknowledge();
  }

  /** Handles one record's value; throws when it cannot be read or stored. */
  public void apply(String body) {
    if (body == null || body.isBlank()) {
      return;
    }
    ConfigChangedEvent e = json.readValue(body, ConfigChangedEvent.class);
    if (e.configKey() == null || e.configType() == null || e.change() == null) {
      throw new IllegalArgumentException("config change without configKey, configType or change");
    }
    menus.onConfigChanged(e);
  }
}
