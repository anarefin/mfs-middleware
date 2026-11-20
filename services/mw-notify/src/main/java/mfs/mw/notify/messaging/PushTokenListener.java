package mfs.mw.notify.messaging;

import mfs.mw.notify.dto.PushTokenEvent;
import mfs.mw.notify.service.PushDeviceService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code mw.notify.push-token.v1} (ticket 110): stores the app's device token for its wallet's
 * bound device ({@link PushDeviceService}). A record that cannot be read (an unknown platform) goes to the
 * DLQ at once; one without wallet, device, token or time throws, so the retry error handler parks it on a
 * retry topic and finally the DLQ. The token is never logged in clear.
 */
@Component("pushTokenListener")
public class PushTokenListener {

  public static final String TOPIC = "mw.notify.push-token.v1";

  private final PushDeviceService devices;
  private final JsonMapper json;

  public PushTokenListener(PushDeviceService devices, JsonMapper json) {
    this.devices = devices;
    this.json = json;
  }

  @KafkaListener(
      id = "pushToken",
      topics = TOPIC,
      groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.notify.consumers.enabled:true}")
  public void handle(ConsumerRecord<String, String> record, Acknowledgment ack) {
    PushTokenEvent e = json.readValue(record.value(), PushTokenEvent.class);
    if (blank(e.walletId()) || blank(e.deviceId()) || blank(e.token()) || e.platform() == null
        || e.registeredAt() == null) {
      throw new IllegalArgumentException("push token without wallet, device, token, platform or time");
    }
    devices.register(e);
    ack.acknowledge();
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
