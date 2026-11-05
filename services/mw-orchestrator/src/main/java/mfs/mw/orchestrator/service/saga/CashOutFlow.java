package mfs.mw.orchestrator.service.saga;

import org.springframework.stereotype.Component;

/**
 * Agent cash out (spec 5.2, WP07): the customer's wallet pays the agent's wallet; the core
 * charges the cash-out fee to the customer. Validate, then PostTransfer in one step; no hold.
 */
@Component
public class CashOutFlow implements Flow {

  @Override
  public String txnType() {
    return "CASH_OUT";
  }

  @Override
  public boolean needsHold() {
    return false;
  }
}
