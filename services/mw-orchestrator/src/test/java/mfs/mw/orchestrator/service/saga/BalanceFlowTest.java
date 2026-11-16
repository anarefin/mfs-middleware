package mfs.mw.orchestrator.service.saga;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import mfs.mw.orchestrator.service.saga.SagaEvent.*;
import org.junit.jupiter.api.Test;

/**
 * BALANCE (ADR D26): no money moves, so the flow is a single BalanceInquiry in VALIDATING, answered
 * COMPLETED or FAILED. No hold, no partner, no post, and no reversal or status inquiry: a balance
 * check has nothing to compensate and an unanswered one simply fails.
 */
class BalanceFlowTest {

  private final Flow flow = new BalanceFlow();

  @Test
  void balanceAsksTheCoreOnceAndHasNoOtherStep() {
    assertThat(flow.txnType()).isEqualTo("BALANCE");
    assertThat(flow.needsHold()).isFalse();
    assertThat(flow.creditsWallet()).isFalse();
    assertThat(flow.partnerCall()).isEmpty();
    assertThat(flow.steps()).containsExactly(Step.BALANCE_INQUIRY);
    for (Step s : Step.values()) {
      assertThat(flow.allows(s)).as(s.name()).isEqualTo(s == Step.BALANCE_INQUIRY);
    }
  }

  @Test
  void noOtherFlowInquiresBalance() {
    for (Flow other : List.of(new P2pFlow(), new TopupFlow(), new AddMoneyBankFlow(), new BillPayFlow())) {
      assertThat(other.allows(Step.BALANCE_INQUIRY)).as(other.txnType()).isFalse();
    }
  }

  @Test
  void theBalanceInquiryEffectIsTheBalanceInquiryStep() {
    assertThat(Step.of(Effect.SEND_BALANCE_INQUIRY)).contains(Step.BALANCE_INQUIRY);
  }

  @Test
  void theSuccessPathIsReceivedValidatingCompleted() {
    List<Effect> effects = new ArrayList<>();
    List<Status> passed = new ArrayList<>();
    Status s = Status.RECEIVED;
    for (SagaEvent e : List.of(new BalanceInquiryStarted(), new BalanceReported())) {
      Transition t = SagaMachine.apply(s, e);
      effects.add(t.effect());
      s = t.to();
      passed.add(s);
    }

    assertThat(passed).containsExactly(Status.VALIDATING, Status.COMPLETED);
    assertThat(effects.stream().map(Step::of).flatMap(Optional::stream)).allMatch(flow::allows);
  }

  @Test
  void aRejectionOrANoAnswerFailsWithNothingToUndo() {
    assertThat(SagaMachine.apply(Status.VALIDATING, new BalanceInquiryRejected()))
        .isEqualTo(new Transition(Status.VALIDATING, Status.FAILED, Effect.NONE));
    assertThat(SagaMachine.apply(Status.VALIDATING, new DeadlineExpired()))
        .isEqualTo(new Transition(Status.VALIDATING, Status.FAILED, Effect.NONE));
  }

  @Test
  void aCompletedBalanceCheckCannotBeReversed() {
    // the machine allows COMPLETED -> REVERSING for money; the flow has no REVERSE step
    Transition t = SagaMachine.apply(Status.COMPLETED, new ReversalRequested());
    assertThat(Step.of(t.effect()).map(flow::allows)).contains(false);
  }
}
