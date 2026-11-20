package mfs.mw.data.export.messaging;

import java.time.Instant;
import java.util.Map;
import mfs.mw.data.export.dto.NotifyStatusEvent;
import mfs.mw.data.export.dto.TxnStatusEvent;
import mfs.mw.data.export.service.DwStreamService;
import mfs.mw.data.export.service.DwTopics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The source topics of the warehouse stream (ticket 128). A record that does not parse, or a field
 * the PII policy does not tag, fails validation and goes to the DLQ. Listeners stay off when
 * {@code mw.export.consumers-enabled} is false (every test except the stream IT).
 */
@Component
public class DwSourceListeners {

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final DwStreamService stream;
  private final JsonMapper json;

  public DwSourceListeners(DwStreamService stream, JsonMapper json) {
    this.stream = stream;
    this.json = json;
  }

  @KafkaListener(id = "dwTxnStatus", topics = DwTopics.SRC_TXN, groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.export.consumers-enabled:true}")
  public void txn(ConsumerRecord<String, String> record, Acknowledgment ack) {
    stream.onTxn(json.readValue(record.value(), TxnStatusEvent.class));
    ack.acknowledge();
  }

  @KafkaListener(id = "dwNotifyStatus", topics = DwTopics.SRC_NOTIFY, groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.export.consumers-enabled:true}")
  public void notify(ConsumerRecord<String, String> record, Acknowledgment ack) {
    stream.onNotify(json.readValue(record.value(), NotifyStatusEvent.class));
    ack.acknowledge();
  }

  @KafkaListener(id = "dwRecon", topics = DwTopics.SRC_RECON, groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.export.consumers-enabled:true}")
  public void recon(ConsumerRecord<String, String> record, Acknowledgment ack) {
    stream.onRecon(json.readValue(record.value(), MAP));
    ack.acknowledge();
  }

  @KafkaListener(id = "dwKyc", topics = DwTopics.SRC_KYC, groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.export.consumers-enabled:true}")
  public void kyc(ConsumerRecord<String, String> record, Acknowledgment ack) {
    stream.onKyc(json.readValue(record.value(), MAP));
    ack.acknowledge();
  }

  @KafkaListener(id = "dwAudit", topics = DwTopics.SRC_AUDIT, groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.export.consumers-enabled:true}")
  public void audit(ConsumerRecord<String, String> record, Acknowledgment ack) {
    Map<String, Object> body = json.readValue(record.value(), MAP);
    Map<String, Object> actor = map(body.get("actor"));
    stream.onOperatorAudit(
        text(body.get("auditId")),
        text(body.get("correlationId")),
        text(actor.get("type")),
        text(actor.get("id")),
        text(body.get("action")),
        text(body.get("resource")),
        text(body.get("outcome")),
        Instant.parse(text(body.get("at"))));
    ack.acknowledge();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
  }

  private static String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }
}
