package mfs.mw.core.mock.entity;

/** Reserved funds of one transaction: {@code amount + fee} stay in the balance but cannot be spent. */
public record Hold(String id, String walletId, String txnId, long amountMinor, long feeMinor, String status) {

  public static final String HELD = "HELD";
  public static final String COMMITTED = "COMMITTED";
  public static final String RELEASED = "RELEASED";

  public long totalMinor() {
    return amountMinor + feeMinor;
  }
}
