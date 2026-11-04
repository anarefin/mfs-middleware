package mfs.mw.orchestrator.service.saga;

import org.springframework.stereotype.Component;

/** Wallet to wallet: Validate, then PostTransfer in one step (spec 6.3). */
@Component
public class P2pFlow implements Flow {

  @Override
  public String txnType() {
    return "P2P";
  }

  @Override
  public boolean needsHold() {
    return false;
  }
}
