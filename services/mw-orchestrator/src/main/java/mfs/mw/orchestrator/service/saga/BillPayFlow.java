package mfs.mw.orchestrator.service.saga;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Bill payment (spec 2.1, WP15): validate, hold the payer's funds, pay the bill through
 * mw-adapter-billpay (the aggregator in {@code partner.partnerId}, the biller in
 * {@code partner.billerCode}, the bill number as the {@code BILLER_ACCOUNT} credit party), then
 * commit the hold on success or release it on a refusal. Same steps as TOPUP.
 */
@Component
public class BillPayFlow implements Flow {

  static final PartnerCall BILLPAY = new PartnerCall("billpay", "mw.partner.request.billpay.v1");

  @Override
  public String txnType() {
    return "BILL_PAY";
  }

  @Override
  public boolean needsHold() {
    return true;
  }

  @Override
  public Optional<PartnerCall> partnerCall() {
    return Optional.of(BILLPAY);
  }
}
