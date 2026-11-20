package mfs.mw.orchestrator.service.saga;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.service.BillerRules.Rule;
import org.junit.jupiter.api.Test;

/** The biller amount rule (ticket 113c, FRS FL-04/FL-05): FIXED, MINIMUM, OPEN. */
class BillPayFlowTest {

  private static final Map<String, String> FETCHED = Map.of("billOutstandingMinor", "66700");

  @Test
  void aBillerWithoutARuleIsNotChecked() {
    assertThat(BillPayFlow.amountRefusal(bill(1, null), Optional.empty())).isNull();
  }

  @Test
  void fixedMustEqualTheFetchedAmount() {
    Rule fixed = new Rule("DESCO", "FIXED", null, null, 1);
    assertThat(BillPayFlow.amountRefusal(bill(66_700, FETCHED), Optional.of(fixed))).isNull();
    for (long amount : new long[] {66_699, 66_701}) {
      ErrorInfo refused = BillPayFlow.amountRefusal(bill(amount, FETCHED), Optional.of(fixed));
      assertThat(refused.code()).isEqualTo("MW-VAL-032");
      assertThat(refused.messageBn()).isNotBlank();
      assertThat(refused.retryable()).isFalse();
    }
  }

  @Test
  void fixedWithoutAFetchedAmountIsLeftToTheAggregator() {
    Rule fixed = new Rule("DESCO", "FIXED", null, null, 1);
    assertThat(BillPayFlow.amountRefusal(bill(10_000, null), Optional.of(fixed))).isNull();
    assertThat(BillPayFlow.amountRefusal(bill(10_000, Map.of("billOutstandingMinor", "x1")), Optional.of(fixed)))
        .isNull();
  }

  @Test
  void minimumIsAtLeastTheMinimumAndHasNoCeiling() {
    Rule minimum = new Rule("AMBERIT", "MINIMUM", 10_000L, null, 1);
    assertThat(BillPayFlow.amountRefusal(bill(9_999, null), Optional.of(minimum)).code()).isEqualTo("MW-VAL-032");
    assertThat(BillPayFlow.amountRefusal(bill(10_000, null), Optional.of(minimum))).isNull();
    assertThat(BillPayFlow.amountRefusal(bill(99_999_999, null), Optional.of(minimum))).isNull();
  }

  @Test
  void openStaysWithinItsBoundsBothInclusive() {
    Rule open = new Rule("DESCO_PRE", "OPEN", 10_000L, 5_000_000L, 1);
    assertThat(BillPayFlow.amountRefusal(bill(9_999, null), Optional.of(open)).code()).isEqualTo("MW-VAL-032");
    assertThat(BillPayFlow.amountRefusal(bill(5_000_001, null), Optional.of(open)).code()).isEqualTo("MW-VAL-032");
    assertThat(BillPayFlow.amountRefusal(bill(10_000, null), Optional.of(open))).isNull();
    assertThat(BillPayFlow.amountRefusal(bill(5_000_000, null), Optional.of(open))).isNull();
  }

  @Test
  void openWithoutBoundsTakesAnyAmount() {
    Rule open = new Rule("X", "OPEN", null, null, 1);
    assertThat(BillPayFlow.amountRefusal(bill(1, null), Optional.of(open))).isNull();
  }

  private static TxnRequest bill(long amount, Map<String, String> attributes) {
    TxnRequest t = TestData.billPay(TestData.txnId(), "key", amount, "1234567");
    return new TxnRequest(
        t.schemaVersion(), t.txnId(), t.idempotencyKey(), t.correlationId(), t.channel(), t.txnType(),
        t.initiator(), t.debitParty(), t.creditParty(), t.amount(), t.partner(), t.originalTxnId(),
        t.assertion(), t.locale(), t.metadata(), attributes, t.requestedAt());
  }
}
