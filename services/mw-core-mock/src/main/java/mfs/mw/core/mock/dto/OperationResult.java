package mfs.mw.core.mock.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The answer of a money operation (contract {@code OperationResult}): built from the operation's
 * stored result event, so a repeat answers exactly the same.
 *
 * @param status HELD, COMMITTED, RELEASED, POSTED, CREDITED or REVERSED
 * @param holdId holds, commits and releases only
 * @param originalTxnId reversals only
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OperationResult(
    String txnId,
    String status,
    String coreRef,
    String holdId,
    String originalTxnId,
    List<CoreEvent.BalanceAfter> balancesAfter) {}
