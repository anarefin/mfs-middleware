package mfs.mw.core.mock.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import mfs.mw.core.mock.util.Sensitive;
import mfs.mw.core.mock.util.SensitiveType;

/**
 * Body of the test-only {@code PUT /core-mock/v1/wallets/{walletId}} (ticket 57,
 * core-mock-seed.v1): the wallet's type, its opening balance in poisha and, optionally, the holder's
 * display name (set on create, or on an existing wallet that has none yet) and, optionally, {@code
 * balanceMinor}: the available balance the wallet must have afterwards, new or existing (the test
 * console's "Set balance"; posted as an ADJUSTMENT double entry against SETTLEMENT). No PIN: the core
 * holds none (ticket 72).
 */
public record WalletSeedRequest(
    @NotNull @Pattern(regexp = "CUSTOMER|AGENT|MERCHANT|ORG") String type,
    @NotNull @PositiveOrZero @Max(100_000_000_000L) Long openingBalanceMinor,
    @Size(min = 1, max = 120) @Pattern(regexp = "(?s).*\\S.*") @Sensitive(SensitiveType.NAME) String holderName,
    @PositiveOrZero @Max(100_000_000_000L) Long balanceMinor) {}
