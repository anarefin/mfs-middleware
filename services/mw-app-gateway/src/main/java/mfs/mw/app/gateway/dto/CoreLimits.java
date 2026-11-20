package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import mfs.mw.app.gateway.util.Sensitive;
import mfs.mw.app.gateway.util.SensitiveType;

/** WAL-05 {@code Limits} as mw-core-mock returns it. The wallet id is the MSISDN and stays out of the app answer. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CoreLimits(
    @Sensitive(SensitiveType.MSISDN) String walletId,
    boolean allowed,
    String reasonCode,
    WalletLimits.Remaining remaining) {}
