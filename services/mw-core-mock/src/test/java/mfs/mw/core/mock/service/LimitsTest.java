package mfs.mw.core.mock.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import mfs.mw.core.mock.entity.LimitRule;
import mfs.mw.core.mock.entity.Usage;
import org.junit.jupiter.api.Test;

/** Limit rule selection by type and KYC level, and the daily and monthly evaluation (ticket 94, WAL-05). */
class LimitsTest {

  private static final LimitRule ANY = new LimitRule("*", "*", "*", 100L, 500_000L, 200, 1_500_000L, 2_000, 20_000_000L);
  private static final LimitRule BASIC =
      new LimitRule("*", "CUSTOMER", "BASIC", 100L, 100_000L, 10, 300_000L, 50, 1_000_000L);
  private static final LimitRule P2P_FULL =
      new LimitRule("P2P", "CUSTOMER", "FULL", 100L, 2_500_000L, 50, 2_500_000L, 300, 20_000_000L);
  private static final LimitRule ORG =
      new LimitRule("*", "ORGANISATION", "*", 100L, 500_000L, null, 5_000_000_000L, null, null);
  private static final List<LimitRule> RULES = List.of(ANY, BASIC, P2P_FULL, ORG);
  private static final Usage NONE = new Usage(0, 0, 0, 0);

  @Test
  void theMostSpecificRuleApplies() {
    assertThat(Limits.rule(RULES, "P2P", "CUSTOMER", "FULL")).contains(P2P_FULL);
    assertThat(Limits.rule(RULES, "CASH_OUT", "CUSTOMER", "FULL")).contains(ANY);
    assertThat(Limits.rule(RULES, "P2P", "CUSTOMER", "BASIC")).contains(BASIC);
    assertThat(Limits.rule(RULES, "BULK_DISBURSE", "ORGANISATION", "FULL")).contains(ORG);
    assertThat(Limits.rule(List.of(BASIC), "P2P", "AGENT", "FULL")).isEmpty();
  }

  @Test
  void basicKycIsHeldToItsLowerLimits() {
    assertThat(Limits.check(BASIC, 150_000, NONE).reason()).contains(Reason.LIMIT_EXCEEDED_PER_TXN);
    assertThat(Limits.check(P2P_FULL, 150_000, NONE).reason()).isEmpty();
  }

  @Test
  void perTransactionMinimumAndMaximum() {
    assertThat(Limits.check(ANY, 99, NONE).reason()).contains(Reason.LIMIT_BELOW_MIN_PER_TXN);
    assertThat(Limits.check(ANY, 100, NONE).reason()).isEmpty();
    assertThat(Limits.check(ANY, 500_000, NONE).reason()).isEmpty();
    assertThat(Limits.check(ANY, 500_001, NONE).reason()).contains(Reason.LIMIT_EXCEEDED_PER_TXN);
  }

  @Test
  void dailyAmountAndCount() {
    assertThat(Limits.check(ANY, 500_000, new Usage(1, 1_000_000, 1, 1_000_000)).reason()).isEmpty();
    assertThat(Limits.check(ANY, 500_000, new Usage(1, 1_000_001, 1, 1_000_001)).reason())
        .contains(Reason.LIMIT_EXCEEDED_DAILY);
    assertThat(Limits.check(ANY, 1_000, new Usage(200, 10_000, 200, 10_000)).reason())
        .contains(Reason.LIMIT_EXCEEDED_DAILY_COUNT);
    assertThat(Limits.check(ANY, 1_000, new Usage(199, 10_000, 199, 10_000)).reason()).isEmpty();
  }

  @Test
  void monthlyAmountAndCount() {
    assertThat(Limits.check(ANY, 1_000, new Usage(0, 0, 10, 19_999_500)).reason())
        .contains(Reason.LIMIT_EXCEEDED_MONTHLY);
    assertThat(Limits.check(ANY, 1_000, new Usage(0, 0, 2_000, 1_000)).reason())
        .contains(Reason.LIMIT_EXCEEDED_MONTHLY_COUNT);
  }

  @Test
  void remainingIsWhatIsLeftBeforeThisAmount() {
    Limits.Result r = Limits.check(ANY, 1_000, new Usage(3, 400_000, 7, 2_000_000));
    assertThat(r.perTxnMax()).isEqualTo(500_000L);
    assertThat(r.dailyLeft()).isEqualTo(1_100_000L);
    assertThat(r.monthlyLeft()).isEqualTo(18_000_000L);
    assertThat(r.dailyCountLeft()).isEqualTo(197L);
    assertThat(r.monthlyCountLeft()).isEqualTo(1_993L);
  }

  @Test
  void aMissingLimitIsNoLimit() {
    Limits.Result r = Limits.check(ORG, 400_000, new Usage(10_000, 4_000_000_000L, 10_000, 9_000_000_000L));
    assertThat(r.reason()).isEmpty();
    assertThat(r.dailyCountLeft()).isNull();
    assertThat(r.monthlyLeft()).isNull();
  }
}
