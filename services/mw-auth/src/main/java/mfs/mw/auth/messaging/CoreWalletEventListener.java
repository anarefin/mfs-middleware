package mfs.mw.auth.messaging;

import mfs.mw.auth.mapper.WalletEventMapper;
import mfs.mw.auth.service.PinResetService;
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
  private final WalletEventMapper mapper;
  private final PinResetService resets;

  public CoreWalletEventListener(WalletProjectionService projection, WalletEventMapper mapper, PinResetService resets) {
    this.projection = projection;
    this.mapper = mapper;
    this.resets = resets;
  }

  /** WalletStateChanged feeds the projection; the answers to VerifyCustomerFactors move a PIN reset (ticket 70). */
  public void handle(Exchange exchange) {
    String body = exchange.getMessage().getBody(String.class);
    projection.handle(body);
    mapper.readFactorsAnswer(body).ifPresent(resets::kycAnswered);
  }
}
