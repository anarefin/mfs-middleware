package mfs.mw.orchestrator.service.saga;

import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Balance check (ADR D26): moves no money. One BalanceInquiry to the core in VALIDATING
 * (shaped like a transfer: amount 0, the customer's own wallet on both sides, mw-auth's assertion),
 * answered BalanceReported (COMPLETED, the balance passed through on the status and never stored)
 * or BalanceInquiryRejected (FAILED); no answer within {@code validating-timeout} fails it
 * ({@code MW-TMO-001}). Unlike every other flow it has no REVERSE (nothing to compensate) and no
 * INQUIRE (it never reaches UNKNOWN).
 */
@Component
public class BalanceFlow implements Flow {

  public static final String TXN_TYPE = "BALANCE";

  @Override
  public String txnType() {
    return TXN_TYPE;
  }

  @Override
  public boolean needsHold() {
    return false;
  }

  @Override
  public Set<Step> steps() {
    return EnumSet.of(Step.BALANCE_INQUIRY);
  }

  @Override
  public boolean allows(Step step) {
    return step == Step.BALANCE_INQUIRY;
  }
}
