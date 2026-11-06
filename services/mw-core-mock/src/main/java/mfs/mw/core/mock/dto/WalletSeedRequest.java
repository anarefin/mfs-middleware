package mfs.mw.core.mock.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Body of the test-only {@code PUT /core-mock/v1/wallets/{walletId}} (ticket 57,
 * core-mock-seed.v1): the wallet's type and its opening balance in poisha. No PIN: a seeded wallet
 * gets the mock's seed PIN.
 */
public record WalletSeedRequest(
    @NotNull @Pattern(regexp = "CUSTOMER|AGENT|MERCHANT|ORG") String type,
    @NotNull @PositiveOrZero @Max(100_000_000_000L) Long openingBalanceMinor) {}
