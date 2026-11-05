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
    SimMode mode) {}
