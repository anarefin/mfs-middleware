package mfs.mw.orchestrator.service.saga;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * W2B, transfer to bank (ticket 117c, FRS UC-15, FL-09): the wallet's money is held, the bank is
 * asked to credit the account (family bank), then the hold is committed on the bank's confirmation
 * or released on a definite refusal. Same steps as TOPUP.
 */
class W2bFlowTest {

  private final Flow flow = new W2bFlow();

  @Test
  void transferToBankHoldsThenCallsTheBank() {
    assertThat(flow.txnType()).isEqualTo("W2B");
    assertThat(flow.needsHold()).isTrue();
    assertThat(flow.creditsWallet()).isFalse();
    assertThat(flow.partnerCall()).contains(new PartnerCall("bank", "mw.partner.request.bank.v1"));
    assertThat(flow.steps())
        .containsExactlyInAnyOrder(Step.VALIDATE, Step.HOLD, Step.CALL_PARTNER, Step.COMMIT, Step.RELEASE);
    assertThat(flow.allows(Step.QUERY)).isTrue();
    assertThat(flow.allows(Step.INQUIRE)).isTrue();
  }
}
