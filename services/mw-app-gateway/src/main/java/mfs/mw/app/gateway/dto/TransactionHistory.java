package mfs.mw.app.gateway.dto;

import java.util.List;

/**
 * {@code GET /app/v1/transactions} (ADR D25): one page of the signed-in wallet's history, newest
 * first; {@code hasMore} when another page follows ({@code offset + limit}).
 */
public record TransactionHistory(List<HistoryItem> items, int limit, int offset, boolean hasMore) {

  public TransactionHistory {
    items = List.copyOf(items);
  }
}
