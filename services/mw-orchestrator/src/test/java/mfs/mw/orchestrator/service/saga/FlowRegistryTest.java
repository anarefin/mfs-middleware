package mfs.mw.orchestrator.service.saga;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * The flows as Spring wires them (component scan of the saga package, like the application):
 * every flow of spec WP07 is registered under its {@code txnType}, and none of them holds funds.
 */
class FlowRegistryTest {

  private static FlowRegistry wired() {
    try (var ctx = new AnnotationConfigApplicationContext(FlowRegistry.class.getPackageName())) {
      return ctx.getBean(FlowRegistry.class);
    }
  }

  @Test
  void cashInIsRegisteredAndPostsWithoutHold() {
    Flow flow = wired().find("CASH_IN").orElseThrow();
    assertThat(flow.txnType()).isEqualTo("CASH_IN");
    assertThat(flow.needsHold()).isFalse();
    assertThat(flow.steps()).containsExactlyInAnyOrder(Step.VALIDATE, Step.POST);
    assertThat(flow.partnerCall()).isEmpty();
  }

  @Test
  void cashOutIsRegisteredAndPostsWithoutHold() {
    Flow flow = wired().find("CASH_OUT").orElseThrow();
    assertThat(flow.txnType()).isEqualTo("CASH_OUT");
    assertThat(flow.needsHold()).isFalse();
    assertThat(flow.steps()).containsExactlyInAnyOrder(Step.VALIDATE, Step.POST);
    assertThat(flow.partnerCall()).isEmpty();
  }

  @Test
  void merchantPayIsRegisteredAndPostsWithoutHold() {
    Flow flow = wired().find("MERCHANT_PAY").orElseThrow();
    assertThat(flow.txnType()).isEqualTo("MERCHANT_PAY");
    assertThat(flow.needsHold()).isFalse();
    assertThat(flow.steps()).containsExactlyInAnyOrder(Step.VALIDATE, Step.POST);
    assertThat(flow.partnerCall()).isEmpty();
  }

  @Test
  void bulkDisburseIsRegisteredAndPostsWithoutHold() {
    Flow flow = wired().find("BULK_DISBURSE").orElseThrow();
    assertThat(flow.needsHold()).isFalse();
    assertThat(flow.steps()).containsExactlyInAnyOrder(Step.VALIDATE, Step.POST);
    assertThat(flow.partnerCall()).isEmpty();
  }

  @Test
  void billPayHoldsAndCallsTheBillpayPartner() {
    Flow flow = wired().find("BILL_PAY").orElseThrow();
    assertThat(flow.needsHold()).isTrue();
    assertThat(flow.partnerCall()).contains(new PartnerCall("billpay", "mw.partner.request.billpay.v1"));
    assertThat(flow.steps())
        .containsExactlyInAnyOrder(Step.VALIDATE, Step.HOLD, Step.CALL_PARTNER, Step.COMMIT, Step.RELEASE);
  }

  @Test
  void p2pStaysRegisteredAndUnknownTypesAreNot() {
    FlowRegistry registry = wired();
    assertThat(registry.find("P2P")).map(Flow::needsHold).contains(false);
    assertThat(registry.find("ATM_CASH_OUT")).isEmpty();
  }
}
