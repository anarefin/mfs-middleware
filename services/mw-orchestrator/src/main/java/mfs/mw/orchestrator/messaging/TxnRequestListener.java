package mfs.mw.orchestrator.messaging;

import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.service.TransactionService;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Handler behind the {@code mw.txn.request.v1} route: one record, one database transaction. A
 * record that cannot be read or lacks a required field throws, so the route parks it on a retry
 * topic and finally the DLQ. The body is never logged (it holds MSISDNs and PIN ciphertext).
 */
@Component("txnRequestListener")
public class TxnRequestListener {

  private final TransactionService service;
  private final JsonMapper json;

  public TxnRequestListener(TransactionService service, JsonMapper json) {
    this.service = service;
    this.json = json;
  }

  public void handle(Exchange exchange) {
    TxnRequest req = json.readValue(exchange.getMessage().getBody(String.class), TxnRequest.class);
    require(req.txnId(), "txnId");
    require(req.idempotencyKey(), "idempotencyKey");
    require(req.correlationId(), "correlationId");
    require(req.channel(), "channel");
    require(req.txnType(), "txnType");
    if (req.initiator() == null || req.initiator().id() == null || req.amount() == null || req.requestedAt() == null) {
      throw new IllegalArgumentException("transaction request lacks initiator, amount or requestedAt");
    }
    service.accept(req, MwHeaders.text(exchange.getMessage().getHeader(MwHeaders.REPLY_TO)));
  }

  private static void require(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("transaction request lacks " + name);
    }
  }
}
