package mfs.mw.orchestrator.service.saga;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** B2B float posts like P2P: validate, then one PostTransfer, no hold and no partner (ticket 106). */
class B2bTransferFlowTest {

  @Test
  void b2bPostsWithoutAHoldOrAPartner() {
    Flow flow = new B2bTransferFlow();

    assertThat(flow.txnType()).isEqualTo("B2B_TRANSFER");
    assertThat(flow.needsHold()).isFalse();
    assertThat(flow.creditsWallet()).isFalse();
    assertThat(flow.partnerCall()).isEmpty();
    assertThat(flow.steps()).containsExactlyInAnyOrder(Step.VALIDATE, Step.POST);
  }

  @Test
  void theRegistryWiresTheFlow() {
    try (var ctx = new AnnotationConfigApplicationContext(FlowRegistry.class.getPackageName())) {
      Flow flow = ctx.getBean(FlowRegistry.class).find("B2B_TRANSFER").orElseThrow();
      assertThat(flow).isInstanceOf(B2bTransferFlow.class);
    }
  }
}
