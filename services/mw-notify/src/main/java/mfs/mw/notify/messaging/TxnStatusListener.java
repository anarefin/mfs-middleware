package mfs.mw.notify.messaging;

import mfs.mw.notify.dto.TxnStatusEvent;
import mfs.mw.notify.service.TxnStatusService;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Handler behind the {@code mw.txn.status.v1} route (spec 10.3): runs the notification rules for
 * the event. A record that cannot be read throws and goes to the retry topics, then the DLQ.
 */
@Component("txnStatusListener")
public class TxnStatusListener {

  private final TxnStatusService service;
  private final JsonMapper json;

  public TxnStatusListener(TxnStatusService service, JsonMapper json) {
    this.service = service;
    this.json = json;
  }

  public void handle(Exchange exchange) {
    TxnStatusEvent event = json.readValue(exchange.getMessage().getBody(String.class), TxnStatusEvent.class);
    if (event.txnId() == null || event.status() == null) {
      throw new IllegalArgumentException("txn status without txnId or status");
    }
    service.onStatus(event);
  }
}
