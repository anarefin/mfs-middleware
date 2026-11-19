package mfs.mw.core.mock.entity;

import java.time.Instant;

/** A ledger entry with its transaction and time, as statements and status reads show it (ticket 93). */
public record PostedEntry(String txnId, String walletId, String direction, String kind, long amountMinor, Instant at) {}
