package mfs.mw.core.mock.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * WAL-13 {@code GET /core/v1/transactions/{txnId}} (contract {@code TransactionStatus}): the core's
 * true status and every ledger entry of the transaction.
 */
public record TransactionStatusView(
    String txnId, String status, @JsonInclude(JsonInclude.Include.NON_NULL) String holdId, List<EntryView> entries) {}
