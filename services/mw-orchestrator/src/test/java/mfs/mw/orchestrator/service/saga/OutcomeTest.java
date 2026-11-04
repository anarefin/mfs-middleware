package mfs.mw.orchestrator.service.saga;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Partner outcomes as the contracts spell them, and which state a late definite outcome contradicts. */
class OutcomeTest {

  @Test
  void parsesTheContractValuesAndNothingElse() {
    assertThat(Outcome.parse("SUCCESS")).contains(Outcome.SUCCESS);
    assertThat(Outcome.parse("NOT_FOUND")).contains(Outcome.NOT_FOUND);
    assertThat(Outcome.parse("PENDING")).contains(Outcome.PENDING);
    assertThat(Outcome.parse("success")).isEmpty();
    assertThat(Outcome.parse("")).isEmpty();
    assertThat(Outcome.parse(null)).isEmpty();
  }

  @Test
  void aLateSuccessContradictsARefundOrAManualReview() {
    assertThat(Outcome.SUCCESS.contradicts(Status.RELEASING)).isTrue();
    assertThat(Outcome.SUCCESS.contradicts(Status.FAILED)).isTrue();
    assertThat(Outcome.SUCCESS.contradicts(Status.MANUAL_REVIEW)).isTrue();
    assertThat(Outcome.SUCCESS.contradicts(Status.COMMITTING)).isFalse();
    assertThat(Outcome.SUCCESS.contradicts(Status.COMPLETED)).isFalse();
  }

  @Test
  void aLateRefusalContradictsAPaymentOrAManualReview() {
    for (Status paid : new Status[] {Status.COMMITTING, Status.COMPLETED, Status.REVERSING, Status.REVERSED}) {
      assertThat(Outcome.FAILED.contradicts(paid)).as(paid.name()).isTrue();
      assertThat(Outcome.NOT_FOUND.contradicts(paid)).as(paid.name()).isTrue();
    }
    assertThat(Outcome.FAILED.contradicts(Status.MANUAL_REVIEW)).isTrue();
    assertThat(Outcome.FAILED.contradicts(Status.FAILED)).isFalse();
  }

  @Test
  void noFinalAnswerContradictsNothing() {
    for (Status s : Status.values()) {
      assertThat(Outcome.PENDING.contradicts(s)).isFalse();
      assertThat(Outcome.UNKNOWN.contradicts(s)).isFalse();
    }
  }
}
