package mfs.mw.core.mock.service;

import java.util.Map;

/**
 * The charges of one transaction (ticket 94, WAL-04), in poisha: the payer pays {@code fee} on top of
 * the amount; it splits into {@code vat} (to VAT_PAYABLE), {@code commission} (the parties' shares,
 * {@code commissionSplit}, to COMMISSION_PAYABLE) and {@code feeIncome} (to FEE_INCOME), which add up
 * to the fee exactly.
 */
public record Charges(long fee, long vat, long commission, Map<String, Long> commissionSplit, long feeIncome) {

  public static final Charges NONE = new Charges(0, 0, 0, Map.of(), 0);

  public Charges {
    commissionSplit = Map.copyOf(commissionSplit);
  }
}
