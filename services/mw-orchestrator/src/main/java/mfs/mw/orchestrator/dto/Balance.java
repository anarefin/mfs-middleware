package mfs.mw.orchestrator.dto;

import java.time.Instant;

/**
 * The core's answer to a BalanceInquiry (ADR D26), {@code balance} on {@code mw.txn.status.v1}:
 * the wallet's available balance at {@code asOf}. Transient: passed from the BalanceReported event
 * to the COMPLETED status and never stored (spec 3 rule 1), so never logged or audited either.
 */
public record Balance(Amount available, Instant asOf) {

  @Override
  public String toString() {
    return "Balance[****]";
  }
}
