package mfs.mw.simulators.dto;

/**
 * Bill fetch reply: status DUE with the amount due, or FAILED / UNAVAILABLE with the
 * aggregator's own code.
 */
public record BillResponse(
    String billerCode,
    String billNumber,
    String status,
    String code,
    String message,
    String amount,
    String dueDate,
    String billPeriod) {}
