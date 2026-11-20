package mfs.mw.simulators.dto;

import java.math.BigDecimal;
import mfs.mw.simulators.util.Sensitive;
import mfs.mw.simulators.util.SensitiveType;

/**
 * A seeded account of the simulated bank gateway (ticket 117d, {@code seed/v1/bank-accounts.csv}): {@code
 * bank} is a bank code or {@code *} (the account exists at every bank). Fake test data.
 */
public record BankAccountSeed(
    String bank,
    @Sensitive(SensitiveType.ACCOUNT) String accountNumber,
    @Sensitive(SensitiveType.NAME) String holderName,
    String status,
    BigDecimal balance) {}
