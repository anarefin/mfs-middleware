package mfs.mw.core.mock.messaging;

import mfs.mw.core.mock.service.CoreCommandService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code core.wallet.command.v1} and {@code core.payment.command.v1} (spec 6.3, the
 * core's Digital Payment side), with retry topics and DLQ (19.2, ADR D27, {@code
 * config/KafkaConfig}): one record, one transaction (ledger, command log, outbox row), then the
 * record is acknowledged. Any command type is accepted on either topic; the reply goes to the
 * event topic that pairs with the command topic. A failure propagates, so the error handler parks
 * the record on a retry topic. Off with {@code mw.coremock.consumer.enabled=false} (the test
 * default). The body is never logged.
 */
@Component
public class CoreCommandListener {

  public static final String WALLET_TOPIC = "core.wallet.command.v1";
  public static final String PAYMENT_TOPIC = "core.payment.command.v1";

  private final CoreCommandService service;

  public CoreCommandListener(CoreCommandService service) {
    this.service = service;
  }

  @KafkaListener(
      id = "core-wallet-command",
      topics = WALLET_TOPIC,
      groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.coremock.consumer.enabled:true}")
  public void handleWallet(ConsumerRecord<String, String> record, Acknowledgment ack) {
    service.handle(record.value(), CoreCommandService.EVENT_TOPIC);
    ack.acknowledge();
  }

  @KafkaListener(
      id = "core-payment-command",
      topics = PAYMENT_TOPIC,
      groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.coremock.consumer.enabled:true}")
  public void handlePayment(ConsumerRecord<String, String> record, Acknowledgment ack) {
    service.handle(record.value(), CoreCommandService.PAYMENT_EVENT_TOPIC);
    ack.acknowledge();
  }
}
