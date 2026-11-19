package mfs.mw.simulators.service;

import mfs.mw.simulators.dto.SimMode;

/**
 * The real outcome of a bill payment, as the aggregator keeps it whatever the caller saw. {@code
 * callbackOnly}: it was accepted with 202 PENDING (and every repeat is), the outcome goes by callback.
 */
public record BillPaymentRecord(
    String transactionId,
    String billerCode,
    String billNumber,
    String amount,
    String status,
    String code,
    String message,
    String paymentRef,
    SimMode mode,
    boolean callbackOnly) {

  /** This record with {@code newCode} (a mock-control BUSINESS_ERROR code); itself when null. */
  public BillPaymentRecord withCode(String newCode) {
    return newCode == null
        ? this
        : new BillPaymentRecord(transactionId, billerCode, billNumber, amount, status, newCode, message, paymentRef, mode, callbackOnly);
  }
}
