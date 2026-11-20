package mfs.mw.notify.messaging;

import mfs.mw.notify.dto.OptOutEvent;
import mfs.mw.notify.service.OptOutService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code mw.notify.opt-out.v1} (ticket 111): the app's promotional opt-out. A record
 * without a wallet id is retried and then parked on the DLQ. The MSISDN is not logged in clear.
 */
@Component("optOutListener")
public class OptOutListener {

  public static final String TOPIC = "mw.notify.opt-out.v1";

  private final OptOutService service;
  private final JsonMapper json;

  public OptOutListener(OptOutService service, JsonMapper json) {
    this.service = service;
    this.json = json;
  }

  @KafkaListener(
      id = "optOut",
      topics = TOPIC,
      groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.notify.consumers.enabled:true}")
  public void handle(ConsumerRecord<String, String> record, Acknowledgment ack) {
    OptOutEvent event = json.readValue(record.value(), OptOutEvent.class);
    if (event.walletId() == null || event.walletId().isBlank()) {
      throw new IllegalArgumentException("opt-out without walletId");
    }
    service.onOptOut(event);
    ack.acknowledge();
  }
}
