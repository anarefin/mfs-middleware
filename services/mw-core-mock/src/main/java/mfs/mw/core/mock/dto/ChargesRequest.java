package mfs.mw.core.mock.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * WAL-04 {@code POST /core/v1/charges/calculate} (contract {@code ChargesRequest}); {@code customerType}
 * is the payer's wallet type (default CUSTOMER), {@code channel} picks a channel row of the tariff.
 */
public record ChargesRequest(
    @NotNull @Pattern(regexp = WalletTransferRequest.TXN_TYPE) String txnType,
    @NotNull @Valid Amount amount,
    @Size(max = 32) String channel,
    @Pattern(regexp = "CUSTOMER|AGENT|MERCHANT|DISTRIBUTOR|ORGANISATION") String customerType) {}
