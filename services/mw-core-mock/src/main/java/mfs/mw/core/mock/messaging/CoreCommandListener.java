package mfs.mw.core.mock.messaging;

import mfs.mw.core.mock.service.CoreCommandService;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

/**
 * Handlers behind the {@code core.wallet.command.v1} and {@code core.payment.command.v1} routes:
 * one record, one transaction (ledger, command log, outbox row). Any command type is accepted on
 * either topic; the reply goes to the event topic that pairs with the command topic. A failure
 * propagates, so the route parks the record on a retry topic. The body is never logged.
 */
@Component("coreCommandListener")
public class CoreCommandListener {

  private final CoreCommandService service;

  public CoreCommandListener(CoreCommandService service) {
    this.service = service;
  }

  public void handleWallet(Exchange exchange) {
    service.handle(exchange.getMessage().getBody(String.class), CoreCommandService.EVENT_TOPIC);
  }

  public void handlePayment(Exchange exchange) {
    service.handle(exchange.getMessage().getBody(String.class), CoreCommandService.PAYMENT_EVENT_TOPIC);
  }
}
