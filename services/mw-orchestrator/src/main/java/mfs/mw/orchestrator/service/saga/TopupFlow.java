package mfs.mw.orchestrator.service.saga;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Airtime top-up (spec 2.1, WP14): validate, hold the payer's funds, ask the operator's OCS through
 * mw-adapter-telco, then commit the hold on success or release it on failure.
 */
@Component
public class TopupFlow implements Flow {

  static final PartnerCall TELCO = new PartnerCall("telco", "mw.partner.request.telco.v1");

  @Override
  public String txnType() {
    return "TOPUP";
  }

  @Override
  public boolean needsHold() {
    return true;
  }

  @Override
  public Optional<PartnerCall> partnerCall() {
    return Optional.of(TELCO);
  }
}
