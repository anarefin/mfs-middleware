package mfs.mw.core.mock.messaging;

import mfs.mw.core.mock.service.CoreCommandService;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

/**
 * Handler behind the {@code core.wallet.command.v1} route: one record, one transaction (ledger,
 * command log, outbox row). A failure propagates, so the route parks the record on a retry topic.
 * The body is never logged.
 */
@Component("coreCommandListener")
public class CoreCommandListener {

  private final CoreCommandService service;

  public CoreCommandListener(CoreCommandService service) {
    this.service = service;
  }

  public void handle(Exchange exchange) {
    service.handle(exchange.getMessage().getBody(String.class));
  }
}
