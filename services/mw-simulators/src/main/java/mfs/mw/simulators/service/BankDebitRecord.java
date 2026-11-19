package mfs.mw.simulators.service;

import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.util.Sensitive;
import mfs.mw.simulators.util.SensitiveType;

/** The real outcome of a C2W debit, as the bank keeps it whatever the caller saw. */
public record BankDebitRecord(
    String transactionId,
    @Sensitive(SensitiveType.ACCOUNT) String accountNumber,
    String amount,
    String status,
    String code,
    String message,
    String debitRef,
    SimMode mode) {

  /** This record with {@code newCode} (a mock-control BUSINESS_ERROR code); itself when null. */
  public BankDebitRecord withCode(String newCode) {
    return newCode == null ? this : new BankDebitRecord(transactionId, accountNumber, amount, status, newCode, message, debitRef, mode);
  }
}
