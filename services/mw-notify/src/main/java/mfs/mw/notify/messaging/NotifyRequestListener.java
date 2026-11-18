package mfs.mw.notify.messaging;

import mfs.mw.notify.dto.NotificationRequest;
import mfs.mw.notify.service.NotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code mw.notify.request.v1} (spec 6.2, 10.1): stores the request (or drops a
 * duplicate) in one transaction and returns; sending happens on the dispatcher, never on the Kafka
 * thread. A record that cannot be read goes to the DLQ at once; one without messageId or
 * templateCode throws, so the retry error handler ({@code config/KafkaConfig}) parks it on a retry
 * topic and finally the DLQ. The body is not logged.
 */
@Component("notifyRequestListener")
public class NotifyRequestListener {

  public static final String TOPIC = "mw.notify.request.v1";

  private final NotificationService notifications;
  private final JsonMapper json;

  public NotifyRequestListener(NotificationService notifications, JsonMapper json) {
    this.notifications = notifications;
    this.json = json;
  }

  @KafkaListener(
      id = "notifyRequest",
      topics = TOPIC,
      groupId = "${spring.kafka.consumer.group-id}",
      autoStartup = "${mw.notify.consumers.enabled:true}")
  public void handle(ConsumerRecord<String, String> record, Acknowledgment ack) {
    NotificationRequest r = json.readValue(record.value(), NotificationRequest.class);
    if (r.messageId() == null || r.messageId().isBlank() || r.templateCode() == null) {
      throw new IllegalArgumentException("notification request without messageId or templateCode");
    }
    notifications.accept(r);
    ack.acknowledge();
  }
}
