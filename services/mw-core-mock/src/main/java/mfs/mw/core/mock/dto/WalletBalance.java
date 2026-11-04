package mfs.mw.core.mock.dto;

import mfs.mw.core.mock.util.Sensitive;
import mfs.mw.core.mock.util.SensitiveType;

/**
 * A wallet's balances as the test/admin API shows them (not a product contract): e2e tests read
 * them to prove money moved exactly once. Minor units (poisha).
 *
 * @param walletId wallet id (the MSISDN for customer, agent, merchant and org wallets)
 * @param availableMinor {@code balanceMinor - heldMinor}: what transfers and holds can still use
 */
public record WalletBalance(
    @Sensitive(SensitiveType.MSISDN) String walletId,
    String type,
    String status,
    long balanceMinor,
    long heldMinor,
    long availableMinor) {}
