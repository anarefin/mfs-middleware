package mfs.mw.orchestrator.service.saga;

import org.springframework.stereotype.Component;

/**
 * In-app merchant payment (spec 5.2, WP20, ticket 30): the customer's wallet pays the merchant's
 * wallet (the core checks CUSTOMER -> MERCHANT and charges the merchant-pay fee, if any, to the
 * payer). Validate, then PostTransfer in one step; no hold and no partner leg (the merchant
 * system notification of 5.2 is out of the MVP).
 */
@Component
public class MerchantPayFlow implements Flow {

  @Override
  public String txnType() {
    return "MERCHANT_PAY";
  }

  @Override
  public boolean needsHold() {
    return false;
  }
}
