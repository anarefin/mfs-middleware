package mfs.mw.simulators.service.mockcontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import mfs.mw.simulators.config.SimulatorProperties;
import mfs.mw.simulators.dto.MockSwitch;
import mfs.mw.simulators.dto.PayShieldScenario;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.dto.UssdSmppScenario;
import mfs.mw.simulators.exception.MwException;
import org.junit.jupiter.api.Test;

/**
 * The old payShield and SMPP USSD gateway control bodies as aliases of the engine (ticket 102b, ADR D33):
 * what a call decides after the old body was posted.
 */
class LegacyScenariosTest {

  final PartySwitch switches = new PartySwitch();
  final ScenarioEngine engine =
      new ScenarioEngine(switches, new SimulatorProperties(Duration.ofMillis(700), 10), new SplittableRandom(3));
  final LegacyScenarios legacy = new LegacyScenarios(engine, switches);

  Decision command(String code) {
    return engine.decide("payshield", "command", Map.of("command", code), SimMode.SUCCESS);
  }

  @Test
  void aPayShieldErrorCodeAppliesToTheListedCommandsOnly() {
    legacy.payShield(new PayShieldScenario(50L, "68", null, List.of("A0")));

    assertThat(command("A0").mode()).isEqualTo(SimMode.BUSINESS_FAILURE);
    assertThat(command("A0").code()).isEqualTo("68");
    assertThat(command("NC").mode()).isEqualTo(SimMode.SUCCESS);
    assertThat(command("NC").delayMs()).isEqualTo(50);
    assertThat(legacy.payShield().errorCode()).isEqualTo("68");
  }

  @Test
  void aPayShieldOutageIsADownSwitchAndAResetForgetsIt() {
    legacy.payShield(new PayShieldScenario(null, null, true, null));
    assertThat(command("NC").gate()).isEqualTo(Decision.Gate.DOWN);

    legacy.forget("payshield");
    assertThat(command("NC").gate()).isEqualTo(Decision.Gate.OPEN);
    assertThat(switches.get("payshield")).isEqualTo(MockSwitch.UP);
  }

  @Test
  void aBadPayShieldErrorCodeIsRefused() {
    assertThatThrownBy(() -> legacy.payShield(new PayShieldScenario(null, "x", null, null)))
        .isInstanceOf(MwException.class);
  }

  @Test
  void theUssdSmppBodyRefusesTheNextScreensInTheOldOrder() {
    legacy.ussd(new UssdSmppScenario(1, 1, 1, true, 500L));

    Map<String, String> keys = Map.of("msisdn", "01711000001");
    assertThat(engine.decide("ussdgw", "submit", keys, SimMode.SUCCESS).mode()).isEqualTo(SimMode.MALFORMED);
    assertThat(engine.decide("ussdgw", "submit", keys, SimMode.SUCCESS).code()).isEqualTo("0x58");
    assertThat(engine.decide("ussdgw", "submit", keys, SimMode.SUCCESS).code()).isEqualTo("0x14");
    assertThat(engine.decide("ussdgw", "submit", keys, SimMode.SUCCESS).mode()).isEqualTo(SimMode.SUCCESS);
    assertThat(engine.decide("ussdgw", "enquire_link", Map.of(), SimMode.SUCCESS).mode()).isEqualTo(SimMode.TIMEOUT);
    assertThat(switches.settings("ussdgw").timeoutDelayMs()).isEqualTo(500L);

    legacy.ussd(new UssdSmppScenario(null, null, null, null, null));
    assertThat(engine.list("ussdgw")).isEmpty();
    assertThat(switches.settings("ussdgw").timeoutDelayMs()).isNull();
  }
}
