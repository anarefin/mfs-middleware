package mfs.mw.auth.messaging;

import mfs.mw.auth.service.WalletProjectionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code core.wallet.event.v1} in group {@code mw-auth} (ticket 67, D5), with retry topics
 * and DLQ (19.2, ADR D27, {@code config/KafkaConfig}): the core's {@code WalletStateChanged} feeds
 * the wallet projection; the topic's other events (the results of the core calls, ticket 95b: the PIN
 * reset's factors check is answered over REST) are skipped. Failures go to {@code core.wallet.event.v1.mw-auth.retry.1..3}, then the DLQ ({@code
 * deploy/kafka/topics.txt} lists the group). A failure propagates (mw-crypto down, malformed
 * event), so the error handler parks the record. Off with {@code mw.auth.consumer.enabled=false}
 * (the test default). The body is never logged.
 */
@Component
public class CoreWalletEventListener {

  public static final String TOPIC = "core.wallet.event.v1";

  private final WalletProjectionService projection;

  public CoreWalletEventListener(WalletProjectionService projection) {
    this.projection = projection;
  }

  @KafkaListener(
      id = "core-wallet-event",
      topics = TOPIC,
      groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.auth.consumer.enabled:true}")
  public void handle(ConsumerRecord<String, String> record, Acknowledgment ack) {
    String body = record.value();
    projection.handle(body);
    ack.acknowledge();
  }
}
