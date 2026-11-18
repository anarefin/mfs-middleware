package mfs.mw.portal.api.messaging;

import mfs.mw.portal.api.dto.StatusMessage;
import mfs.mw.portal.api.service.BulkExecutionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code mw.txn.status.v1} (group {@code mw-portal-api}) to settle the rows of bulk
 * batches from their children's status records, with retry topics and DLQ (spec 6.2, 19.2;
 * ticket 34; ADR D27, {@code config/KafkaConfig}). Hands every status record to {@link
 * BulkExecutionService#apply}, which keeps only the org portal's bulk children, then acknowledges
 * it. Applying is a conditional update, so a redelivered record is a no-op and needs no inbox row.
 * A record that cannot be read throws, so the error handler parks it on a retry topic and finally
 * the DLQ. Short database work only, never a wait, on the consumer thread. Off with {@code
 * mw.portal-api.consumers.enabled=false} (the test default).
 */
@Component
public class TxnStatusListener {

  public static final String TOPIC = "mw.txn.status.v1";

  private final BulkExecutionService execution;
  private final JsonMapper json;

  public TxnStatusListener(BulkExecutionService execution, JsonMapper json) {
    this.execution = execution;
    this.json = json;
  }

  @KafkaListener(
      id = "txn-status",
      topics = TOPIC,
      groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.portal-api.consumers.enabled:true}")
  public void handle(ConsumerRecord<String, String> record, Acknowledgment ack) {
    execution.apply(json.readValue(record.value(), StatusMessage.class));
    ack.acknowledge();
  }
}
