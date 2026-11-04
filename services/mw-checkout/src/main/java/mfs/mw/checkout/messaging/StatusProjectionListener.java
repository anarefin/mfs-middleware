package mfs.mw.checkout.messaging;

import mfs.mw.checkout.dto.StatusMessage;
import mfs.mw.checkout.mapper.CheckoutMapper;
import mfs.mw.checkout.service.SessionOutcomes;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Handler behind the shared {@code mw.txn.status.v1} route (group {@code mw-checkout}): hands the
 * final status of every WEB_CHECKOUT transaction to {@link SessionOutcomes}. A record that cannot
 * be read throws, so the route parks it on a retry topic and finally the DLQ.
 */
@Component("statusProjectionListener")
public class StatusProjectionListener {

  private final SessionOutcomes outcomes;
  private final JsonMapper json;

  public StatusProjectionListener(SessionOutcomes outcomes, JsonMapper json) {
    this.outcomes = outcomes;
    this.json = json;
  }

  public void handle(Exchange exchange) {
    StatusMessage status = json.readValue(exchange.getMessage().getBody(String.class), StatusMessage.class);
    if (CheckoutMapper.CHANNEL.equals(status.channel())) {
      outcomes.apply(status);
    }
  }
}
