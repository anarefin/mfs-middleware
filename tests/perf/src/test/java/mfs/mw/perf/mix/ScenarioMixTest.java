package mfs.mw.perf.mix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class ScenarioMixTest {

  @Test
  void the_default_mix_covers_every_mvp_flow_and_adds_up_to_the_target_rate() {
    ScenarioMix mix = ScenarioMix.defaults();

    assertThat(mix.flows()).containsExactlyInAnyOrderElementsOf(EnumSet.allOf(Flow.class));
    double total = mix.flows().stream().mapToDouble(f -> mix.ratePerSecond(f, 100)).sum();
    assertThat(total).isEqualTo(100.0, org.assertj.core.data.Offset.offset(1e-9));
  }

  @Test
  void the_default_mix_is_the_documented_one() {
    ScenarioMix mix = ScenarioMix.defaults();

    // README.md "Scenario mix" at 100 TPS
    assertThat(mix.ratePerSecond(Flow.P2P, 100)).isEqualTo(30.0);
    assertThat(mix.ratePerSecond(Flow.CASH_IN, 100)).isEqualTo(10.0);
    assertThat(mix.ratePerSecond(Flow.CASH_OUT, 100)).isEqualTo(10.0);
    assertThat(mix.ratePerSecond(Flow.MERCHANT_PAY, 100)).isEqualTo(13.0);
    assertThat(mix.ratePerSecond(Flow.TOPUP, 100)).isEqualTo(13.0);
    assertThat(mix.ratePerSecond(Flow.TOPUP_REFUSED, 100)).isEqualTo(2.0);
    assertThat(mix.ratePerSecond(Flow.BILL_PAY, 100)).isEqualTo(10.0);
    assertThat(mix.ratePerSecond(Flow.ADD_MONEY_BANK, 100)).isEqualTo(7.0);
    assertThat(mix.ratePerSecond(Flow.USSD_P2P, 100)).isEqualTo(5.0);
  }

  @Test
  void an_override_is_scaled_to_the_target_rate_and_drops_unlisted_flows() {
    ScenarioMix mix = ScenarioMix.parse("P2P=3, BILL_PAY=1");

    assertThat(mix.flows()).containsExactlyInAnyOrder(Flow.P2P, Flow.BILL_PAY);
    assertThat(mix.ratePerSecond(Flow.P2P, 100)).isEqualTo(75.0);
    assertThat(mix.ratePerSecond(Flow.BILL_PAY, 100)).isEqualTo(25.0);
    assertThat(mix.ratePerSecond(Flow.CASH_IN, 100)).isZero();
  }

  @Test
  void a_blank_override_means_the_default_mix() {
    assertThat(ScenarioMix.parse(" ")).isEqualTo(ScenarioMix.defaults());
    assertThat(ScenarioMix.parse(null)).isEqualTo(ScenarioMix.defaults());
  }

  @Test
  void a_bad_override_is_refused() {
    assertThatThrownBy(() -> ScenarioMix.parse("P2P=1,REFUND=2")).hasMessageContaining("REFUND");
    assertThatThrownBy(() -> ScenarioMix.parse("P2P=-1")).hasMessageContaining("P2P");
    assertThatThrownBy(() -> ScenarioMix.parse("P2P=0")).hasMessageContaining("weight");
    assertThatThrownBy(() -> ScenarioMix.parse("P2P")).hasMessageContaining("P2P");
  }

  @Test
  void only_the_intended_business_failures_are_expected_to_fail() {
    assertThat(EnumSet.allOf(Flow.class).stream().filter(Flow::intendedFailure))
        .containsExactly(Flow.TOPUP_REFUSED);
  }
}
