package mfs.mw.core.mock.dto;

import mfs.mw.core.mock.util.Sensitive;
import mfs.mw.core.mock.util.SensitiveType;

/**
 * WAL-01 {@code GET /core/v1/accounts/{msisdn}} (contract {@code core-wallet.v1} {@code Account}). The
 * KYC level of FRS 7.2 comes with the core model (ticket 94).
 *
 * @param msisdn null for system wallets
 * @param holderName null for system wallets; personal data, the first initial only in logs
 */
public record AccountView(
    @Sensitive(SensitiveType.MSISDN) String walletId,
    @Sensitive(SensitiveType.MSISDN) String msisdn,
    String type,
    String status,
    @Sensitive(SensitiveType.NAME) String holderName) {}
