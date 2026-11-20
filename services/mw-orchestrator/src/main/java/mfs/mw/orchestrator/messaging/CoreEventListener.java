package mfs.mw.orchestrator.messaging;

import mfs.mw.orchestrator.dto.CoreEvent;
import mfs.mw.orchestrator.service.CoreEventService;
import mfs.mw.orchestrator.service.CoreTopics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code core.wallet.event.v1} and {@code core.payment.event.v1} (ADR D31). Since ticket 95a
 * the saga takes the core's answers from its REST calls, so {@link CoreEventService} ignores the
 * result events the core still publishes for them and applies only HoldExpired (ticket 95c). Wallet-state
 * events are consumed and move no saga. A failure propagates, so the retry error handler (config/KafkaConfig) parks the record on a
 * retry topic. The body is never logged.
 */
@Component("coreEventListener")
public class CoreEventListener {

  private final CoreEventService service;
  private final JsonMapper json;

  public CoreEventListener(CoreEventService service, JsonMapper json) {
    this.service = service;
    this.json = json;
  }

  @KafkaListener(
      id = "coreEvent",
      topics = {CoreTopics.WALLET_EVENTS, CoreTopics.PAYMENT_EVENTS},
      groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.orchestrator.consumers.enabled:true}")
  public void handle(ConsumerRecord<String, String> record, Acknowledgment ack) {
    CoreEvent event = json.readValue(record.value(), CoreEvent.class);
    if (event.eventId() == null || event.eventId().isBlank() || event.txnId() == null) {
      throw new IllegalArgumentException("core event lacks eventId or txnId");
    }
    service.handle(event);
    ack.acknowledge();
  }
}
