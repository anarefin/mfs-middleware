package mfs.mw.audit.dw.messaging;

import mfs.mw.audit.dw.dto.TxnStatusEvent;
import mfs.mw.audit.dw.service.TxnIndexService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code mw.txn.status.v1} (owner mw-orchestrator) into the search projection {@code
 * audit.txn_index} (ticket 130, ADR D27): one record, one upsert, then the record is acknowledged.
 * A record that cannot be read or lacks a needed field throws and moves through the retry topics
 * ({@code mw.txn.status.v1.mw-audit-dw.retry.1..3}) to the DLQ, as does a tokenisation mw-crypto
 * could not answer. Off with {@code mw.audit-dw.consumers.enabled=false} (the test default). The
 * body (it carries clear party ids) is never logged.
 */
@Component
public class TxnStatusListener {

  public static final String TOPIC = "mw.txn.status.v1";

  private final TxnIndexService service;
  private final JsonMapper json;

  public TxnStatusListener(TxnIndexService service, JsonMapper json) {
    this.service = service;
    this.json = json;
  }

  @KafkaListener(
      id = "txn-status",
      topics = TOPIC,
      groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.audit-dw.consumers.enabled:true}")
  public void handle(ConsumerRecord<String, String> record, Acknowledgment ack) {
    service.apply(json.readValue(record.value(), TxnStatusEvent.class));
    ack.acknowledge();
  }
}
