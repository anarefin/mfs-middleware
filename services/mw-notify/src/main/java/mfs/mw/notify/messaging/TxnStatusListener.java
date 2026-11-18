package mfs.mw.notify.messaging;

import mfs.mw.notify.dto.TxnStatusEvent;
import mfs.mw.notify.service.TxnStatusService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code mw.txn.status.v1} once for this service (group {@code mw-notify}, spec 6.2,
 * 10.3) and runs the notification rules for each event. A record that cannot be read goes to the
 * DLQ at once; one without txnId or status throws, so the retry error handler ({@code
 * config/KafkaConfig}) parks it on a retry topic and finally the DLQ.
 */
@Component("txnStatusListener")
public class TxnStatusListener {

  public static final String TOPIC = "mw.txn.status.v1";

  private final TxnStatusService service;
  private final JsonMapper json;

  public TxnStatusListener(TxnStatusService service, JsonMapper json) {
    this.service = service;
    this.json = json;
  }

  @KafkaListener(
      id = "txnStatus",
      topics = TOPIC,
      groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.notify.consumers.enabled:true}")
  public void handle(ConsumerRecord<String, String> record, Acknowledgment ack) {
    TxnStatusEvent event = json.readValue(record.value(), TxnStatusEvent.class);
    if (event.txnId() == null || event.status() == null) {
      throw new IllegalArgumentException("txn status without txnId or status");
    }
    service.onStatus(event);
    ack.acknowledge();
  }
}
