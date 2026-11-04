package mfs.mw.orchestrator.service.saga;

import org.springframework.stereotype.Component;

/**
 * Agent cash in (spec 5.2, WP07): the agent's wallet (float) pays the customer's wallet.
 * Validate, then PostTransfer in one step; no external party, so no hold.
 */
@Component
public class CashInFlow implements Flow {

  @Override
  public String txnType() {
    return "CASH_IN";
  }

  @Override
  public boolean needsHold() {
    return false;
  }
}
