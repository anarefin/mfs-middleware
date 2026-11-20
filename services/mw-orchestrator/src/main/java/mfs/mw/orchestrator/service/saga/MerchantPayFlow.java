package mfs.mw.orchestrator.service.saga;

import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * In-app merchant payment (spec 5.2, WP20, ticket 30): the customer's wallet pays the merchant's
 * wallet (the core checks CUSTOMER -> MERCHANT and charges the merchant-pay fee, if any, to the
 * payer). Validate, then PostTransfer in one step; no hold.
 *
 * <p>A merchant with its own system (ticket 115b, {@code mw.orchestrator.merchant-systems}) adds
 * the merchant leg on {@link #MERCHANT}: a VALIDATE (partner INQUIRY) before the core's validate, and
 * a best-effort NOTIFY (partner PAY) after COMPLETED that never reverses the payment. That leg is not
 * {@link #partnerCall()}: the outcome of the payment is the core's, so an UNKNOWN posting is still
 * inquired at the core.
 */
@Component
public class MerchantPayFlow implements Flow {

  public static final String TXN_TYPE = "MERCHANT_PAY";

  /** The merchant system leg (ticket 115b). */
  public static final PartnerCall MERCHANT = new PartnerCall("merchant", "mw.partner.request.merchant.v1");

  @Override
  public String txnType() {
    return TXN_TYPE;
  }

  @Override
  public boolean needsHold() {
    return false;
  }

  /** Validate + post, plus the merchant VALIDATE call (sent only for a merchant with its own system). */
  @Override
  public Set<Step> steps() {
    return EnumSet.of(Step.VALIDATE, Step.POST, Step.CALL_PARTNER);
  }
}
