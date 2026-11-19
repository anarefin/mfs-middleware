package mfs.mw.core.mock.entity;

/**
 * What a wallet already paid in the scope of a limit rule (ticket 94): transactions and poisha of
 * transfer debits since the start of the UTC day and of the UTC month.
 */
public record Usage(long dailyCount, long dailyAmount, long monthlyCount, long monthlyAmount) {}
