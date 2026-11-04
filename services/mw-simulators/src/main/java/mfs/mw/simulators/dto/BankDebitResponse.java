package mfs.mw.simulators.dto;

/**
 * Debit reply and callback body of the C2W bank: status PENDING | SUCCESS | FAILED | NOT_FOUND |
 * UNAVAILABLE | ERROR, the bank's code and its own reference {@code debitRef}.
 */
public record BankDebitResponse(
    String transactionId, String status, String code, String message, String debitRef) {}
