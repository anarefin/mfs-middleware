package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * The signed-in wallet's ledger statement (ticket 107b, WAL-12): entries in {@code [from, to)},
 * oldest first, one page. The wallet is the token's, so the rows do not carry it.
 */
public record LedgerStatement(Instant from, Instant to, int page, boolean hasMore, List<LedgerEntry> entries) {

  /** One ledger row. {@code balanceAfter} is absent on entries older than ticket 94. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record LedgerEntry(
      String txnId, String direction, String kind, Amount amount, Amount balanceAfter, Instant at) {}
}
