package mfs.mw.orchestrator.service.saga;

import org.springframework.stereotype.Component;

/**
 * B2B float (ticket 106, UC-07): the caller's wallet pays another wallet. Validate, then
 * PostTransfer in one step, the same shape as {@link P2pFlow}. No hold and no partner.
 */
@Component
public class B2bTransferFlow implements Flow {

  @Override
  public String txnType() {
    return "B2B_TRANSFER";
  }

  @Override
  public boolean needsHold() {
    return false;
  }
}
