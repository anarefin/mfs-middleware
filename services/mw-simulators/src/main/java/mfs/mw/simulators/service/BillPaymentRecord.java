package mfs.mw.simulators.service;

import mfs.mw.simulators.dto.SimMode;

/** The real outcome of a bill payment, as the aggregator keeps it whatever the caller saw. */
public record BillPaymentRecord(
    String transactionId,
    String billerCode,
    String billNumber,
    String amount,
    String status,
    String code,
    String message,
    String paymentRef,
    SimMode mode) {}
