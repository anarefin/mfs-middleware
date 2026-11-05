package mfs.mw.orchestrator.messaging;

import mfs.mw.orchestrator.dto.TxnAction;
import mfs.mw.orchestrator.service.OperatorActionService;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Handler behind the {@code mw.txn.action.v1} route (ticket 37). A record that cannot be read or
 * lacks its ids throws, so the route parks it on a retry topic and finally the DLQ. The body is
 * never logged (the reason is free text).
 */
@Component("txnActionListener")
public class TxnActionListener {

  private final OperatorActionService service;
  private final JsonMapper json;

  public TxnActionListener(OperatorActionService service, JsonMapper json) {
    this.service = service;
    this.json = json;
  }

  public void handle(Exchange exchange) {
    TxnAction action = json.readValue(exchange.getMessage().getBody(String.class), TxnAction.class);
    if (blank(action.actionId()) || blank(action.txnId()) || blank(action.action()) || blank(action.checkerId())) {
      throw new IllegalArgumentException("operator action lacks actionId, txnId, action or checkerId");
    }
    service.handle(action);
  }

  private static boolean blank(String v) {
    return v == null || v.isBlank();
  }
}
