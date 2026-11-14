package mfs.mw.auth.messaging;

import mfs.mw.auth.service.WalletProjectionService;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

/**
 * Handler behind {@code route/CoreWalletEventRoute} (ticket 67). A failure propagates, so the route
 * parks the record on a retry topic (mw-crypto down, malformed event). The body is never logged.
 */
@Component("coreWalletEventListener")
public class CoreWalletEventListener {

  private final WalletProjectionService projection;

  public CoreWalletEventListener(WalletProjectionService projection) {
    this.projection = projection;
  }

  public void handle(Exchange exchange) {
    projection.handle(exchange.getMessage().getBody(String.class));
  }
}
