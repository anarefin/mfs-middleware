package mfs.mw.core.mock.entity;

/**
 * One row of the limit sheet (ticket 94, FRS 7.1; table {@code limit_rule}, seeded from {@code
 * seed/v1/limits.csv}). {@code txnType}, {@code customerType} and {@code kycLevel} may be {@code *}
 * (any); a rule for one txnType counts only that type's debits, a {@code *} rule every type's. A
 * {@code null} limit is no limit. Amounts in poisha, counts in transactions.
 */
public record LimitRule(
    String txnType,
    String customerType,
    String kycLevel,
    Long perTxnMin,
    Long perTxnMax,
    Integer dailyCount,
    Long dailyAmount,
    Integer monthlyCount,
    Long monthlyAmount) {

  public static final String ANY = "*";

  /** No rule matched: nothing is limited. */
  public static final LimitRule NONE = new LimitRule(ANY, ANY, ANY, null, null, null, null, null, null);

  /** Whether this rule counts only the debits of its own txnType. */
  public boolean perType() {
    return !ANY.equals(txnType);
  }
}
