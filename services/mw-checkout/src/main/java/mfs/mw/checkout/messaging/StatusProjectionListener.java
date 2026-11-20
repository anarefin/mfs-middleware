package mfs.mw.checkout.messaging;

import mfs.mw.checkout.dto.StatusMessage;
import mfs.mw.checkout.mapper.CheckoutMapper;
import mfs.mw.checkout.mapper.RefundMapper;
import mfs.mw.checkout.service.RefundOutcomes;
import mfs.mw.checkout.service.SessionOutcomes;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared consumer of {@code mw.txn.status.v1} (consumer group {@code mw-checkout}, retry topics
 * and DLQ from {@code config/KafkaConfig}; spec 6.2, 19.2, ADR D27): hands the final status of
 * every WEB_CHECKOUT transaction to {@link SessionOutcomes}, so every payment outcome reaches its
 * session, also one that comes after the reply budget; a REVERSAL (merchant refund, ticket 116)
 * goes to {@link RefundOutcomes}. A record that cannot be read throws, so
 * the error handler parks it on a retry topic and finally the DLQ (a schema failure goes to the
 * DLQ at once). Off when {@code mw.checkout.consumers.enabled=false}.
 */
@Component
public class StatusProjectionListener {

  private final SessionOutcomes outcomes;
  private final RefundOutcomes refunds;
  private final JsonMapper json;

  public StatusProjectionListener(SessionOutcomes outcomes, RefundOutcomes refunds, JsonMapper json) {
    this.outcomes = outcomes;
    this.refunds = refunds;
    this.json = json;
  }

  @KafkaListener(
      id = "status-projection",
      topics = "${mw.checkout.status-topic}",
      groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.checkout.consumers.enabled:true}")
  public void onStatus(ConsumerRecord<String, String> record, Acknowledgment ack) {
    StatusMessage status = json.readValue(record.value(), StatusMessage.class);
    if (CheckoutMapper.CHANNEL.equals(status.channel())) {
      if (RefundMapper.REVERSAL.equals(status.txnType())) {
        refunds.apply(status); // a merchant refund (ticket 116)
      } else {
        outcomes.apply(status);
      }
    }
    ack.acknowledge();
  }
}
