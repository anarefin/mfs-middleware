package mfs.mw.core.mock.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** WAL-10 {@code POST /core/v1/credits}: money in from the settlement wallet (contract {@code CreditRequest}). */
public record WalletCreditRequest(
    @NotNull @Pattern(regexp = WalletTransferRequest.TXN_ID) String txnId,
    @NotNull @Pattern(regexp = WalletTransferRequest.TXN_TYPE) String txnType,
    @NotNull @Valid Party creditParty,
    @NotNull @Valid Amount amount) {}
