package mfs.mw.core.mock.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import mfs.mw.core.mock.util.Sensitive;
import mfs.mw.core.mock.util.SensitiveType;

/**
 * One ledger entry as WAL-12 and WAL-13 serve it (contract {@code Entry}); {@code balanceAfter} is the
 * wallet's balance right after it (ticket 94; absent for entries older than that).
 */
public record EntryView(
    String txnId,
    @Sensitive(SensitiveType.MSISDN) String walletId,
    String direction,
    String kind,
    Amount amount,
    @JsonInclude(JsonInclude.Include.NON_NULL) Amount balanceAfter,
    Instant at) {}
