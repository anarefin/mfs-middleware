package mfs.mw.notify.messaging;

import mfs.mw.notify.dto.WalletLockEvent;
import mfs.mw.notify.service.WalletLockService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code auth.wallet-lock.v1} (ticket 111) in group {@code mw-notify}: a LOCK or UNLOCK
 * becomes the notice rules; other actions are stored as nothing. A record that cannot be read goes
 * to the DLQ; one without commandId, action or walletId throws, so it is retried and then parked.
 */
@Component("walletLockListener")
public class WalletLockListener {

  public static final String TOPIC = "auth.wallet-lock.v1";

  private final WalletLockService service;
  private final JsonMapper json;

  public WalletLockListener(WalletLockService service, JsonMapper json) {
    this.service = service;
    this.json = json;
  }

  @KafkaListener(
      id = "walletLock",
      topics = TOPIC,
      groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.notify.consumers.enabled:true}")
  public void handle(ConsumerRecord<String, String> record, Acknowledgment ack) {
    WalletLockEvent event = json.readValue(record.value(), WalletLockEvent.class);
    if (blank(event.commandId()) || blank(event.action()) || blank(event.walletId())) {
      throw new IllegalArgumentException("wallet lock without commandId, action or walletId");
    }
    service.onCommand(event);
    ack.acknowledge();
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
