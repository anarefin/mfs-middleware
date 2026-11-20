package mfs.mw.notify.messaging;

import mfs.mw.notify.dto.ConfigChangedEvent;
import mfs.mw.notify.service.CampaignService;
import mfs.mw.notify.service.NotifyRulePublisher;
import mfs.mw.notify.service.TemplatePublisher;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code mw.config.changed.v1} (compacted; spec 20.4, ticket 39) in group {@code
 * mw-notify}, earliest offset, so a service started on an empty group reads the latest record of
 * every key: hands each record to {@link TemplatePublisher} (template versions), {@link
 * CampaignService} (campaigns) and {@link NotifyRulePublisher} (rules, ticket 133c), each storing
 * its own config type in one short transaction and ignoring the others. A tombstone (empty body, compaction) is
 * skipped. A record that cannot be read goes to the DLQ at once; one without its keys, or a store
 * that fails, throws, so the retry error handler ({@code config/KafkaConfig}) parks it on a retry
 * topic and finally the DLQ. The body is not logged.
 */
@Component("configChangedListener")
public class ConfigChangedListener {

  public static final String TOPIC = "mw.config.changed.v1";

  private final TemplatePublisher templates;
  private final CampaignService campaigns;
  private final NotifyRulePublisher rules;
  private final JsonMapper json;

  public ConfigChangedListener(
      TemplatePublisher templates, CampaignService campaigns, NotifyRulePublisher rules, JsonMapper json) {
    this.templates = templates;
    this.campaigns = campaigns;
    this.rules = rules;
    this.json = json;
  }

  @KafkaListener(
      id = "configChanged",
      topics = TOPIC,
      groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.notify.consumers.enabled:true}")
  public void handle(ConsumerRecord<String, String> record, Acknowledgment ack) {
    String body = record.value();
    if (body != null && !body.isBlank()) {
      ConfigChangedEvent e = json.readValue(body, ConfigChangedEvent.class);
      if (e.configKey() == null || e.configType() == null || e.change() == null) {
        throw new IllegalArgumentException("config change without configKey, configType or change");
      }
      templates.onConfigChanged(e);
      campaigns.onConfigChanged(e);
      rules.onConfigChanged(e);
    }
    ack.acknowledge();
  }
}
