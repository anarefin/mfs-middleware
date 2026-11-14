package mfs.mw.auth.messaging;

import mfs.mw.auth.service.WalletLockService;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

/**
 * Handler behind {@code route/WalletLockRoute} (ticket 70). A failure propagates, so the route parks
 * the record on a retry topic. The body is never logged.
 */
@Component("walletLockListener")
public class WalletLockListener {

  private final WalletLockService locks;

  public WalletLockListener(WalletLockService locks) {
    this.locks = locks;
  }

  public void handle(Exchange exchange) {
    locks.handle(exchange.getMessage().getBody(String.class));
  }
}
