package mfs.mw.core.mock.dto;

/**
 * WAL-04 answer (contract {@code Charges}): {@code total} = amount + fee + vat, what the payer is
 * debited. VAT and commission are 0 until the tariff sheet (ticket 94).
 */
public record ChargesView(String txnType, Amount amount, Amount fee, Amount vat, Amount commission, Amount total) {}
