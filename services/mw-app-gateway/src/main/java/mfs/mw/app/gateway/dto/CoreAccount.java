package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/** WAL-01 {@code Account} as mw-core-mock returns it (contract {@code core-wallet.v1}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CoreAccount(
    @Sensitive(SensitiveType.MSISDN) String walletId,
    @Sensitive(SensitiveType.MSISDN) String msisdn,
    String type,
    String status,
    String kycLevel,
    @Sensitive(SensitiveType.NAME) String holderName) {}
