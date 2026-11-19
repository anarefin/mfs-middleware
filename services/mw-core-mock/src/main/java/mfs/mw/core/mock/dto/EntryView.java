package mfs.mw.core.mock.dto;

import java.time.Instant;
import mfs.mw.core.mock.util.Sensitive;
import mfs.mw.core.mock.util.SensitiveType;

/** One ledger entry as WAL-12 and WAL-13 serve it (contract {@code Entry}). */
public record EntryView(
    String txnId, @Sensitive(SensitiveType.MSISDN) String walletId, String direction, String kind, Amount amount, Instant at) {}
