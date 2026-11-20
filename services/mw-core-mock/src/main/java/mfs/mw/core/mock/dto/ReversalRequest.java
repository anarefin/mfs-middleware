package mfs.mw.core.mock.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * WAL-11 {@code POST /core/v1/reversals} (contract {@code ReversalRequest}): {@code txnId} is the
 * reversal's own; {@code amount} absent = the whole original (a smaller amount is a partial
 * reversal, ticket 116).
 */
public record ReversalRequest(
    @NotNull @Pattern(regexp = WalletTransferRequest.TXN_ID) String txnId,
    @NotNull @Pattern(regexp = WalletTransferRequest.TXN_ID) String originalTxnId,
    @Valid Amount amount,
    @Size(max = 200) String reason) {}
