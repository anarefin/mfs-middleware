package mfs.mw.core.mock.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * The answer of {@code POST /core/v1/transactions/validate} (contract {@code ValidationResult}, ticket
 * 95a): the transaction would pass, at this fee (VAT included) and VAT, with what is left of the
 * payer's limits. Built from the stored TransactionValidated event, so a repeat answers the same.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ValidationResult(
    String txnId, String status, String coreRef, Amount fee, Amount vat, Map<String, Amount> limitsLeft) {}
