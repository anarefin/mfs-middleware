package mfs.mw.orchestrator.service.saga;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import mfs.mw.orchestrator.service.saga.SagaEvent.*;
import org.junit.jupiter.api.Test;

/**
 * TOPUP (spec 7.1, WP14) is built from the shared steps validate, hold, callPartner, commit and
 * release, and the hold path of the machine only asks for steps the flow has.
 */
class TopupFlowTest {

  private final Flow topup = new TopupFlow();
  private final Flow p2p = new P2pFlow();

  @Test
  void topupHoldsAndCallsTheTelcoPartner() {
    assertThat(topup.txnType()).isEqualTo("TOPUP");
    assertThat(topup.needsHold()).isTrue();
    assertThat(topup.partnerCall()).contains(new PartnerCall("telco", "mw.partner.request.telco.v1"));
    assertThat(topup.steps())
        .containsExactlyInAnyOrder(Step.VALIDATE, Step.HOLD, Step.CALL_PARTNER, Step.COMMIT, Step.RELEASE);
  }

  @Test
  void p2pReusesTheValidateStepAndPostsWithoutPartner() {
    assertThat(p2p.partnerCall()).isEmpty();
    assertThat(p2p.steps()).containsExactlyInAnyOrder(Step.VALIDATE, Step.POST);
    assertThat(p2p.steps()).containsAnyElementsOf(topup.steps());
  }

  @Test
  void theRegistryFindsBothFlows() {
    FlowRegistry registry = new FlowRegistry(List.of(p2p, topup));
    assertThat(registry.find("TOPUP")).containsSame(topup);
    assertThat(registry.find("P2P")).containsSame(p2p);
    assertThat(registry.find("BILL_PAY")).isEmpty();
  }

  @Test
  void theSuccessPathAsksOnlyForStepsOfTheFlow() {
    List<Effect> effects =
        walk(
            new ValidationStarted(), new Validated(topup.needsHold()), new HoldPlaced(),
            new PartnerSucceeded(), new CommitAcknowledged());

    assertThat(effects)
        .containsExactly(Effect.SEND_VALIDATE, Effect.SEND_HOLD, Effect.SEND_PARTNER_REQUEST, Effect.SEND_COMMIT, Effect.NONE);
    assertThat(effects.stream().map(Step::of).flatMap(java.util.Optional::stream))
        .allMatch(topup.steps()::contains);
  }

  @Test
  void aPartnerFailureReleasesTheHoldAndFails() {
    Status end = Status.RECEIVED;
    List<Status> passed = new ArrayList<>();
    for (SagaEvent e :
        List.of(new ValidationStarted(), new Validated(true), new HoldPlaced(), new PartnerFailed(), new ReleaseAcknowledged())) {
      Transition t = SagaMachine.apply(end, e);
      end = t.to();
      passed.add(end);
    }
    assertThat(passed)
        .containsExactly(Status.VALIDATING, Status.HOLDING, Status.PARTNER_PENDING, Status.RELEASING, Status.FAILED);
  }

  @Test
  void commitAndReleaseRetryInPlaceOnTheirDeadline() {
    Transition commit = SagaMachine.apply(Status.COMMITTING, new DeadlineExpired());
    Transition release = SagaMachine.apply(Status.RELEASING, new DeadlineExpired());
    assertThat(commit.changesState()).isFalse();
    assertThat(Step.of(commit.effect())).contains(Step.COMMIT);
    assertThat(release.changesState()).isFalse();
    assertThat(Step.of(release.effect())).contains(Step.RELEASE);
  }

  @Test
  void aHoldingTimeoutQueriesTheCoreAndAHeldAnswerReleases() {
    Transition timeout = SagaMachine.apply(Status.HOLDING, new DeadlineExpired());
    assertThat(timeout.changesState()).isFalse();
    assertThat(Step.of(timeout.effect())).contains(Step.QUERY);
    assertThat(SagaMachine.apply(Status.HOLDING, new HoldQueryResult(true)).to()).isEqualTo(Status.RELEASING);
    assertThat(SagaMachine.apply(Status.HOLDING, new HoldQueryResult(false)).to()).isEqualTo(Status.REJECTED);
  }

  @Test
  void effectsWithoutAStepAreNotSteps() {
    assertThat(Step.of(Effect.NONE)).isEmpty();
    assertThat(Step.of(Effect.SCHEDULE_INQUIRY)).isEmpty();
    assertThat(Step.of(Effect.SEND_REVERSE)).contains(Step.REVERSE);
  }

  private static List<Effect> walk(SagaEvent... events) {
    Status s = Status.RECEIVED;
    List<Effect> out = new ArrayList<>();
    for (SagaEvent e : events) {
      Transition t = SagaMachine.apply(s, e);
      out.add(t.effect());
      s = t.to();
    }
    return out;
  }
}
