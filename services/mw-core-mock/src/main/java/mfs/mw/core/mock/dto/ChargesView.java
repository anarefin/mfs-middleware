package mfs.mw.core.mock.dto;

import java.util.Map;

/**
 * WAL-04 answer (contract {@code Charges}, ticket 94): the tariff row's {@code fee}, VAT included
 * ({@code vat} is the part of it that is VAT), {@code commission} the agents' and distributors' shares
 * of it ({@code commissionSplit} by party), {@code total} = amount + fee, what the payer is debited.
 */
public record ChargesView(
    String txnType, Amount amount, Amount fee, Amount vat, Amount commission, Map<String, Amount> commissionSplit,
    Amount total) {}
