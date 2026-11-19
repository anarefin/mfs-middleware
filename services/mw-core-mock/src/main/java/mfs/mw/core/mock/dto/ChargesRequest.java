package mfs.mw.core.mock.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** WAL-04 {@code POST /core/v1/charges/calculate} (contract {@code ChargesRequest}). */
public record ChargesRequest(
    @NotNull @Pattern(regexp = WalletTransferRequest.TXN_TYPE) String txnType,
    @NotNull @Valid Amount amount,
    @Size(max = 32) String channel) {}
