package mfs.mw.core.mock.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import mfs.mw.core.mock.util.Sensitive;
import mfs.mw.core.mock.util.SensitiveType;

/** WAL-05 {@code POST /core/v1/limits/check} (contract {@code LimitsRequest}). */
public record LimitsRequest(
    @NotBlank @Size(max = 64) @Sensitive(SensitiveType.MSISDN) String walletId,
    @NotNull @Pattern(regexp = WalletTransferRequest.TXN_TYPE) String txnType,
    @NotNull @Valid Amount amount) {}
