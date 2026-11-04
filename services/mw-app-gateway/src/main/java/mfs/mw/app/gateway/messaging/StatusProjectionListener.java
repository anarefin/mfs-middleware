package mfs.mw.app.gateway.messaging;

import java.util.Set;
import mfs.mw.app.gateway.dto.StatusMessage;
import mfs.mw.app.gateway.service.StatusProjectionService;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Handler behind the shared {@code mw.txn.status.v1} route (consumer group {@code
 * mw-app-gateway}): keeps the latest status of every app-channel transaction for {@code GET}. A
 * record that cannot be read throws, so the route parks it on a retry topic and finally the DLQ.
 */
@Component("statusProjectionListener")
public class StatusProjectionListener {

  /** Channels served by this service (spec 5.1); other channels' statuses are skipped. */
  static final Set<String> APP_CHANNELS =
      Set.of("CUSTOMER_APP", "AGENT_APP", "MERCHANT_APP", "DISTRIBUTOR_APP", "DSO_APP", "PARTNER_APP");

  private final StatusProjectionService projection;
  private final JsonMapper json;

  public StatusProjectionListener(StatusProjectionService projection, JsonMapper json) {
    this.projection = projection;
    this.json = json;
  }

  public void handle(Exchange exchange) {
    StatusMessage status =
        json.readValue(exchange.getMessage().getBody(String.class), StatusMessage.class);
    if (APP_CHANNELS.contains(status.channel())) {
      projection.apply(status);
    }
  }
}
