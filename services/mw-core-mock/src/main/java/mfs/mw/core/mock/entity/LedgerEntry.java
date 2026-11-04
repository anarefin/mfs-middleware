package mfs.mw.core.mock.entity;

/** One side of a posting (own schema only). */
public record LedgerEntry(String walletId, String direction, String kind, long amountMinor) {

  public boolean debit() {
    return "DEBIT".equals(direction);
  }
}
