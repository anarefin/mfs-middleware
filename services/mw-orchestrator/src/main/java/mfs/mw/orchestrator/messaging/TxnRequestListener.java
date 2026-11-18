package mfs.mw.orchestrator.messaging;

import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.service.TransactionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code mw.txn.request.v1}: one record, one database transaction. A
 * record that cannot be read goes to the DLQ at once; one that lacks a required field throws, so the retry error handler (config/KafkaConfig) parks it on a retry
 * topic and finally the DLQ. The body is never logged (it holds MSISDNs and PIN ciphertext).
 */
@Component("txnRequestListener")
public class TxnRequestListener {

  private final TransactionService service;
  private final JsonMapper json;

  public TxnRequestListener(TransactionService service, JsonMapper json) {
    this.service = service;
    this.json = json;
  }

  @KafkaListener(
      id = "txnRequest",
      topics = MwTopics.TXN_REQUEST,
      groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.orchestrator.consumers.enabled:true}")
  public void handle(ConsumerRecord<String, String> record, Acknowledgment ack) {
    TxnRequest req = json.readValue(record.value(), TxnRequest.class);
    require(req.txnId(), "txnId");
    require(req.idempotencyKey(), "idempotencyKey");
    require(req.correlationId(), "correlationId");
    require(req.channel(), "channel");
    require(req.txnType(), "txnType");
    if (req.initiator() == null || req.initiator().id() == null || req.amount() == null || req.requestedAt() == null) {
      throw new IllegalArgumentException("transaction request lacks initiator, amount or requestedAt");
    }
    service.accept(req, MwHeaders.text(record.headers().lastHeader(MwHeaders.REPLY_TO)));
    ack.acknowledge();
  }

  private static void require(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("transaction request lacks " + name);
    }
  }
}
