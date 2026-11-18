package mfs.mw.orchestrator.messaging;

import mfs.mw.orchestrator.dto.TxnAction;
import mfs.mw.orchestrator.service.OperatorActionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code mw.txn.action.v1} (ticket 37). A record that cannot be read goes to the DLQ at once; one that
 * lacks its ids throws, so the retry error handler (config/KafkaConfig) parks it on a retry topic and finally the DLQ. The body is
 * never logged (the reason is free text).
 */
@Component("txnActionListener")
public class TxnActionListener {

  private final OperatorActionService service;
  private final JsonMapper json;

  public TxnActionListener(OperatorActionService service, JsonMapper json) {
    this.service = service;
    this.json = json;
  }

  @KafkaListener(
      id = "txnAction",
      topics = "mw.txn.action.v1",
      groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.orchestrator.consumers.enabled:true}")
  public void handle(ConsumerRecord<String, String> record, Acknowledgment ack) {
    TxnAction action = json.readValue(record.value(), TxnAction.class);
    if (blank(action.actionId()) || blank(action.txnId()) || blank(action.action()) || blank(action.checkerId())) {
      throw new IllegalArgumentException("operator action lacks actionId, txnId, action or checkerId");
    }
    service.handle(action);
    ack.acknowledge();
  }

  private static boolean blank(String v) {
    return v == null || v.isBlank();
  }
}
