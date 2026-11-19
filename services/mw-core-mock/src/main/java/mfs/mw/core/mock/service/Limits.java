package mfs.mw.core.mock.service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import mfs.mw.core.mock.entity.LimitRule;
import mfs.mw.core.mock.entity.Usage;

/**
 * Limit evaluation (ticket 94, FRS 7.1, WAL-05), pure. The rule: txnType, customer (wallet) type and
 * KYC level each equal or {@code *}; of several the most specific (txnType counts most, then the
 * customer type, then the KYC level), then the first. The checks, in order: per-transaction minimum
 * and maximum, daily amount, daily count, monthly amount, monthly count (this transaction included);
 * a {@code null} limit is none.
 */
public final class Limits {

  private Limits() {}

  /**
   * The outcome: the refusal, if any, and what is left before this amount (null where unlimited).
   *
   * @param perTxnMax the per-transaction maximum
   */
  public record Result(
      Optional<Reason> reason, Long perTxnMax, Long dailyLeft, Long monthlyLeft, Long dailyCountLeft, Long monthlyCountLeft) {}

  public static Optional<LimitRule> rule(List<LimitRule> rules, String txnType, String customerType, String kycLevel) {
    return rules.stream()
        .filter(r -> matches(r.txnType(), txnType) && matches(r.customerType(), customerType)
            && matches(r.kycLevel(), kycLevel))
        .max(Comparator.comparingInt(Limits::specificity));
  }

  public static Result check(LimitRule r, long amount, Usage used) {
    Reason reason = null;
    if (r.perTxnMin() != null && amount < r.perTxnMin()) {
      reason = Reason.LIMIT_BELOW_MIN_PER_TXN;
    } else if (r.perTxnMax() != null && amount > r.perTxnMax()) {
      reason = Reason.LIMIT_EXCEEDED_PER_TXN;
    } else if (r.dailyAmount() != null && used.dailyAmount() + amount > r.dailyAmount()) {
      reason = Reason.LIMIT_EXCEEDED_DAILY;
    } else if (r.dailyCount() != null && used.dailyCount() + 1 > r.dailyCount()) {
      reason = Reason.LIMIT_EXCEEDED_DAILY_COUNT;
    } else if (r.monthlyAmount() != null && used.monthlyAmount() + amount > r.monthlyAmount()) {
      reason = Reason.LIMIT_EXCEEDED_MONTHLY;
    } else if (r.monthlyCount() != null && used.monthlyCount() + 1 > r.monthlyCount()) {
      reason = Reason.LIMIT_EXCEEDED_MONTHLY_COUNT;
    }
    return new Result(Optional.ofNullable(reason), r.perTxnMax(),
        left(r.dailyAmount(), used.dailyAmount()), left(r.monthlyAmount(), used.monthlyAmount()),
        r.dailyCount() == null ? null : left((long) r.dailyCount(), used.dailyCount()),
        r.monthlyCount() == null ? null : left((long) r.monthlyCount(), used.monthlyCount()));
  }

  private static Long left(Long limit, long used) {
    return limit == null ? null : Math.max(0, limit - used);
  }

  private static boolean matches(String ruleValue, String value) {
    return LimitRule.ANY.equals(ruleValue) || ruleValue.equals(value);
  }

  private static int specificity(LimitRule r) {
    return (r.perType() ? 4 : 0) + (LimitRule.ANY.equals(r.customerType()) ? 0 : 2)
        + (LimitRule.ANY.equals(r.kycLevel()) ? 0 : 1);
  }
}
