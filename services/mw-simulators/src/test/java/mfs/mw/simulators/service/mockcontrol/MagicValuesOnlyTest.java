package mfs.mw.simulators.service.mockcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import mfs.mw.simulators.config.SimulatorProperties;
import mfs.mw.simulators.dto.CallbackMode;
import mfs.mw.simulators.dto.MockSettings;
import mfs.mw.simulators.dto.SimMode;
import org.junit.jupiter.api.Test;

/** Without the mock-control profile every call follows its magic value; no callbacks, no switch. */
class MagicValuesOnlyTest {

  private final MagicValuesOnly control = new MagicValuesOnly(new SimulatorProperties(Duration.ofMillis(250), 10));

  @Test
  void theMagicValueDecidesAlone() {
    Decision d = control.decide("ocs", "topup", Map.of("msisdn", "01712345678"), SimMode.TIMEOUT);
    assertThat(d.gate()).isEqualTo(Decision.Gate.OPEN);
    assertThat(d.mode()).isEqualTo(SimMode.TIMEOUT);
    assertThat(d.holdMs()).isEqualTo(250);
    assertThat(d.callback()).isEqualTo(CallbackMode.NONE);
    assertThat(control.settings("ocs")).isEqualTo(MockSettings.DEFAULT);
  }
}
