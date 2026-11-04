package mfs.mw.orchestrator.service.saga;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import mfs.mw.orchestrator.service.saga.SagaEvent.*;
import org.junit.jupiter.api.Test;

/**
 * ADD_MONEY_BANK / C2W (ticket 23, spec WP16): money comes in from the customer's bank account, so
 * nothing is held; validate, ask the bank to debit the account (partner leg, family bank), then
 * credit the wallet (CreditWallet is this flow's commit step). A refusal by the bank fails with
 * nothing to release.
 */
class AddMoneyBankFlowTest {

  private final Flow flow = new AddMoneyBankFlow();

  @Test
  void addMoneyCallsTheBankAndCreditsTheWalletWithoutAHold() {
    assertThat(flow.txnType()).isEqualTo("ADD_MONEY_BANK");
    assertThat(flow.needsHold()).isFalse();
    assertThat(flow.creditsWallet()).isTrue();
    assertThat(flow.partnerCall()).contains(new PartnerCall("bank", "mw.partner.request.bank.v1"));
    assertThat(flow.steps()).containsExactlyInAnyOrder(Step.VALIDATE, Step.CALL_PARTNER, Step.COMMIT);
  }

  @Test
  void otherFlowsDoNotCreditTheWallet() {
    assertThat(new TopupFlow().creditsWallet()).isFalse();
    assertThat(new P2pFlow().creditsWallet()).isFalse();
  }

  @Test
  void theSuccessPathAsksOnlyForStepsOfTheFlow() {
    List<Effect> effects = new ArrayList<>();
    List<Status> passed = new ArrayList<>();
    Status s = Status.RECEIVED;
    for (SagaEvent e :
        List.of(new ValidationStarted(), new Validated(false, true), new PartnerSucceeded(), new CommitAcknowledged())) {
      Transition t = SagaMachine.apply(s, e);
      effects.add(t.effect());
      s = t.to();
      passed.add(s);
    }

    assertThat(passed).containsExactly(Status.VALIDATING, Status.PARTNER_PENDING, Status.COMMITTING, Status.COMPLETED);
    assertThat(effects.stream().map(Step::of).flatMap(Optional::stream)).allMatch(flow.steps()::contains);
  }

  @Test
  void aBankRefusalFailsWithNothingToRelease() {
    Transition t = SagaMachine.apply(Status.PARTNER_PENDING, new PartnerFailed(false));
    assertThat(t.to()).isEqualTo(Status.FAILED);
    assertThat(t.effect()).isEqualTo(Effect.NONE);
  }

  @Test
  void anInquiryResolvesToCreditingOrFailing() {
    assertThat(SagaMachine.apply(Status.UNKNOWN, new InquirySucceeded(true)).to()).isEqualTo(Status.COMMITTING);
    assertThat(SagaMachine.apply(Status.UNKNOWN, new InquiryFailed(false)).to()).isEqualTo(Status.FAILED);
  }
}
