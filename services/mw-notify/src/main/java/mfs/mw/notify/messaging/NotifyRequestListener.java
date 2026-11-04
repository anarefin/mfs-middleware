package mfs.mw.notify.messaging;

import mfs.mw.notify.dto.NotificationRequest;
import mfs.mw.notify.service.NotificationService;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Handler behind the {@code mw.notify.request.v1} route (spec 10.1): stores the request (or drops
 * a duplicate) in one transaction and returns; sending happens on the dispatcher, never on the
 * Kafka thread. A record that cannot be read throws, so the route parks it on a retry topic and
 * finally the DLQ. The body is not logged.
 */
@Component("notifyRequestListener")
public class NotifyRequestListener {

  private final NotificationService notifications;
  private final JsonMapper json;

  public NotifyRequestListener(NotificationService notifications, JsonMapper json) {
    this.notifications = notifications;
    this.json = json;
  }

  public void handle(Exchange exchange) {
    NotificationRequest r = json.readValue(exchange.getMessage().getBody(String.class), NotificationRequest.class);
    if (r.messageId() == null || r.messageId().isBlank() || r.templateCode() == null) {
      throw new IllegalArgumentException("notification request without messageId or templateCode");
    }
    notifications.accept(r);
  }
}
