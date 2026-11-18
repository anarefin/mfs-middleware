package mfs.mw.auth.messaging;

import mfs.mw.auth.service.WalletLockService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code auth.wallet-lock.v1} in group {@code mw-auth} (ticket 70, spec 18.7 Revocation),
 * with retry topics and DLQ (19.2, ADR D27, {@code config/KafkaConfig}): LOCK, UNLOCK,
 * REVOKE_SESSIONS, PIN_RESET from mw-admin-api. Failures go to {@code
 * auth.wallet-lock.v1.mw-auth.retry.1..3}, then the DLQ ({@code deploy/kafka/topics.txt}). A
 * failure propagates, so the error handler parks the record. Off with {@code
 * mw.auth.consumer.enabled=false} (the test default). The body is never logged.
 */
@Component
public class WalletLockListener {

  public static final String TOPIC = "auth.wallet-lock.v1";

  private final WalletLockService locks;

  public WalletLockListener(WalletLockService locks) {
    this.locks = locks;
  }

  @KafkaListener(
      id = "auth-wallet-lock",
      topics = TOPIC,
      groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.auth.consumer.enabled:true}")
  public void handle(ConsumerRecord<String, String> record, Acknowledgment ack) {
    locks.handle(record.value());
    ack.acknowledge();
  }
}
