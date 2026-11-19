package mfs.mw.core.mock.entity;

import java.time.Instant;

/**
 * A ledger entry with its transaction and time, as statements and status reads show it (ticket 93),
 * and the wallet's balance right after it (ticket 94; null for entries written before V13).
 */
public record PostedEntry(
    String txnId, String walletId, String direction, String kind, long amountMinor, Long balanceAfterMinor, Instant at) {}
