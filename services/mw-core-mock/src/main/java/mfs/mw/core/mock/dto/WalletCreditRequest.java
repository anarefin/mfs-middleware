package mfs.mw.core.mock.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * WAL-10 {@code POST /core/v1/credits}: money in from a settlement wallet (contract {@code CreditRequest}):
 * the partner's one {@code settlementWallet} names (ticket 94: SETTLEMENT_NPSB, SETTLEMENT_UCB_IMT, ...),
 * else SETTLEMENT. {@code round} (ticket 95a): an operator's forced credit, decided afresh as its own
 * operation ({@code <txnId>:rest:credit:manual-<n>}); the txn is still credited once.
 */
public record WalletCreditRequest(
    @NotNull @Pattern(regexp = WalletTransferRequest.TXN_ID) String txnId,
    @NotNull @Pattern(regexp = WalletTransferRequest.TXN_TYPE) String txnType,
    @NotNull @Valid Party creditParty,
    @NotNull @Valid Amount amount,
    @Size(min = 1, max = 64) @Pattern(regexp = WalletTransferRequest.SETTLEMENT) String settlementWallet,
    @Min(0) @Max(1000) Integer round) {}
