package mfs.mw.simulators.dto;

/** Payment reply: status SUCCESS | FAILED | NOT_FOUND | UNAVAILABLE | ERROR, with the aggregator's code. */
public record BillPaymentResponse(
    String transactionId, String status, String code, String message, String paymentRef) {}
