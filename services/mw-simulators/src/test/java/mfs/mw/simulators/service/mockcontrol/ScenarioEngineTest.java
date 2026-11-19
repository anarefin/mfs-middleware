package mfs.mw.simulators.service.mockcontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.SplittableRandom;
import mfs.mw.simulators.config.SimulatorProperties;
import mfs.mw.simulators.dto.CallbackMode;
import mfs.mw.simulators.dto.MockBehaviour;
import mfs.mw.simulators.dto.MockScenario;
import mfs.mw.simulators.dto.MockSettings;
import mfs.mw.simulators.dto.MockSwitch;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.exception.MwException;
import org.junit.jupiter.api.Test;

/** The lookup order of ADR D33 / ticket 102: next-N, key, operation, party-wide, magic value, SUCCESS. */
class ScenarioEngineTest {

  private static final String A = "01712345678";
  private static final String B = "01812345678";

  private final PartySwitch switches = new PartySwitch();
  private final ScenarioEngine engine =
      new ScenarioEngine(switches, new SimulatorProperties(Duration.ofMillis(700), 10), new SplittableRandom(7));

  private Decision topup(String msisdn, SimMode magic) {
    return engine.decide("ocs", "topup", Map.of("msisdn", msisdn, "txnRef", "T-" + msisdn), magic);
  }

  @Test
  void theMagicValueDecidesWhenNothingIsSet() {
    Decision d = topup(A, SimMode.TIMEOUT);
    assertThat(d.mode()).isEqualTo(SimMode.TIMEOUT);
    assertThat(d.source()).isEqualTo("magic");
    assertThat(d.holdMs()).as("sim.timeout-delay").isEqualTo(700);
    assertThat(topup(A, SimMode.SUCCESS).source()).isEqualTo("default");
    assertThat(topup(A, SimMode.SUCCESS).gate()).isEqualTo(Decision.Gate.OPEN);
  }

  @Test
  void anExplicitScenarioBeatsTheMagicValue() {
    engine.add("ocs", MockScenario.of("topup", MockBehaviour.SUCCESS));
    assertThat(topup(A, SimMode.TIMEOUT).mode()).isEqualTo(SimMode.SUCCESS);
  }

  @Test
  void aKeyScopeBeatsAGlobalOne() {
    engine.add("ocs", MockScenario.of(null, MockBehaviour.BUSINESS_ERROR).withCode("2009"));
    engine.add("ocs", MockScenario.of(null, MockBehaviour.HTTP_5XX).withHttpStatus(502).withKey(A));
    Decision a = topup(A, SimMode.SUCCESS);
    assertThat(a.mode()).isEqualTo(SimMode.SERVER_ERROR_AFTER_SEND);
    assertThat(a.httpStatus()).isEqualTo(502);
    Decision b = topup(B, SimMode.SUCCESS);
    assertThat(b.mode()).isEqualTo(SimMode.BUSINESS_FAILURE);
    assertThat(b.code()).isEqualTo("2009");
  }

  @Test
  void anOperationScopeBeatsAPartyWideOne() {
    engine.add("ocs", MockScenario.of("topup", MockBehaviour.MALFORMED_RESPONSE));
    engine.add("ocs", MockScenario.of(null, MockBehaviour.PARTIAL_SUCCESS));
    assertThat(topup(A, SimMode.SUCCESS).mode()).isEqualTo(SimMode.MALFORMED);
    assertThat(engine.decide("ocs", "status", Map.of("txnRef", "x"), SimMode.SUCCESS).mode())
        .isEqualTo(SimMode.PARTIAL_SUCCESS);
  }

  @Test
  void nextNCountsDownThenTheLowerLevelsDecideAgain() {
    engine.add("ocs", MockScenario.of(null, MockBehaviour.BUSINESS_ERROR).withKey(A));
    MockScenario n = engine.add("ocs", MockScenario.of("topup", MockBehaviour.CONNECTION_RESET).withNextN(2));
    assertThat(n.remaining()).isEqualTo(2);
    assertThat(topup(A, SimMode.SUCCESS).mode()).as("next-N beats the key scope").isEqualTo(SimMode.CONNECTION_RESET);
    assertThat(engine.list("ocs")).filteredOn(s -> s.id().equals(n.id())).singleElement()
        .extracting(MockScenario::remaining).isEqualTo(1);
    assertThat(topup(B, SimMode.SUCCESS).mode()).isEqualTo(SimMode.CONNECTION_RESET);
    assertThat(engine.list("ocs")).extracting(MockScenario::id).doesNotContain(n.id());
    assertThat(topup(A, SimMode.SUCCESS).mode()).isEqualTo(SimMode.BUSINESS_FAILURE);
    assertThat(topup(B, SimMode.UNKNOWN_CODE).mode()).isEqualTo(SimMode.UNKNOWN_CODE);
  }

  @Test
  void aNextNScenarioForAnotherKeyIsNotConsumed() {
    MockScenario n = engine.add("ocs", MockScenario.of(null, MockBehaviour.TIMEOUT).withKey(A).withNextN(1).withDelayMs(5L));
    assertThat(topup(B, SimMode.SUCCESS).mode()).isEqualTo(SimMode.SUCCESS);
    Decision a = topup(A, SimMode.SUCCESS);
    assertThat(a.mode()).isEqualTo(SimMode.TIMEOUT);
    assertThat(a.holdMs()).isEqualTo(5);
    assertThat(engine.list("ocs")).extracting(MockScenario::id).doesNotContain(n.id());
  }

  @Test
  void delayAndCallbackAreDecidedBesideTheOutcome() {
    switches.settings("ocs", new MockSettings("http://cb", false, null));
    engine.add("ocs", MockScenario.of(null, MockBehaviour.DELAY).withDelayMs(40L));
    engine.add("ocs", MockScenario.of(null, MockBehaviour.LATE_CALLBACK).withDelayMs(900L));
    Decision d = topup(A, SimMode.BUSINESS_FAILURE);
    assertThat(d.mode()).as("the magic value still decides the outcome").isEqualTo(SimMode.BUSINESS_FAILURE);
    assertThat(d.delayMs()).isEqualTo(40);
    assertThat(d.callback()).isEqualTo(CallbackMode.LATE);
    assertThat(d.callbackDelayMs()).isEqualTo(900);
  }

  @Test
  void callbacksGoOnceWhenAUrlIsSetAndNeverWithoutOne() {
    assertThat(topup(A, SimMode.SUCCESS).callback()).isEqualTo(CallbackMode.NONE);
    engine.add("ocs", MockScenario.of(null, MockBehaviour.DUPLICATE_CALLBACK));
    assertThat(topup(A, SimMode.SUCCESS).callback()).as("no target, no callback").isEqualTo(CallbackMode.NONE);
    switches.settings("ocs", new MockSettings("http://cb", false, 50L));
    assertThat(topup(A, SimMode.SUCCESS).callback()).isEqualTo(CallbackMode.DUPLICATE);
    engine.clear("ocs");
    Decision d = topup(A, SimMode.TIMEOUT);
    assertThat(d.callback()).isEqualTo(CallbackMode.ONCE);
    assertThat(d.holdMs()).as("the party's timeout hold").isEqualTo(50);
  }

  @Test
  void aDownOrMaintenancePartyConsumesNoScenario() {
    MockScenario n = engine.add("ocs", MockScenario.of(null, MockBehaviour.TIMEOUT).withNextN(1));
    switches.set("ocs", new MockSwitch(MockSwitch.State.DOWN, null, null, null));
    assertThat(topup(A, SimMode.SUCCESS).gate()).isEqualTo(Decision.Gate.DOWN);
    switches.set("ocs", new MockSwitch(MockSwitch.State.MAINTENANCE, null, null, null));
    assertThat(topup(A, SimMode.SUCCESS).gate()).isEqualTo(Decision.Gate.MAINTENANCE);
    assertThat(engine.list("ocs")).extracting(MockScenario::id).contains(n.id());
    switches.set("ocs", MockSwitch.UP);
    assertThat(topup(A, SimMode.SUCCESS).mode()).isEqualTo(SimMode.TIMEOUT);
  }

  @Test
  void slowDrawsALatencyInTheRange() {
    switches.set("ocs", new MockSwitch(MockSwitch.State.SLOW, 100L, 200L, null));
    for (int i = 0; i < 20; i++) {
      assertThat(topup(A, SimMode.SUCCESS).delayMs()).isBetween(100L, 200L);
    }
  }

  @Test
  void aRatioBelowOneHitsOnlyAShareOfTheCalls() {
    switches.set("ocs", new MockSwitch(MockSwitch.State.MAINTENANCE, null, null, 0.5));
    long hit = java.util.stream.IntStream.range(0, 200)
        .filter(i -> topup(A, SimMode.SUCCESS).gate() == Decision.Gate.MAINTENANCE).count();
    assertThat(hit).isBetween(50L, 150L);
  }

  @Test
  void scenariosCanBeRemovedOneByOneOrAllAtOnce() {
    MockScenario s = engine.add("ocs", MockScenario.of(null, MockBehaviour.TIMEOUT));
    assertThat(engine.remove("ocs", s.id())).isTrue();
    assertThat(engine.remove("ocs", s.id())).isFalse();
    engine.add("ocs", MockScenario.of(null, MockBehaviour.TIMEOUT));
    engine.clear("ocs");
    assertThat(engine.list("ocs")).isEmpty();
  }

  @Test
  void anUnknownPartyIsRefused() {
    assertThatThrownBy(() -> engine.add("nope", MockScenario.of(null, MockBehaviour.TIMEOUT)))
        .isInstanceOf(MwException.class);
    assertThatThrownBy(() -> engine.decide("nope", "x", Map.of(), SimMode.SUCCESS)).isInstanceOf(MwException.class);
  }

  @Test
  void aWirePartyRefusesABehaviourItCannotDoAndACodeNotInItsForm() {
    assertThatThrownBy(() -> engine.add("payshield", MockScenario.of(null, MockBehaviour.HTTP_5XX)))
        .isInstanceOf(MwException.class).hasMessageContaining("cannot do HTTP_5XX");
    assertThatThrownBy(() -> engine.add("smsc", MockScenario.of("submit", MockBehaviour.BUSINESS_ERROR).withCode("x58")))
        .isInstanceOf(MwException.class);
    assertThat(engine.add("smsc", MockScenario.of("submit", MockBehaviour.BUSINESS_ERROR).withCode("0x58")).id())
        .isNotNull();
    assertThat(MockParties.smppStatus("0x58")).isEqualTo(88);
    assertThat(MockParties.smppStatus("88")).isEqualTo(88);
  }

  @Test
  void theSmscDlrIsACallbackWithoutAUrl() {
    Map<String, String> keys = Map.of("msisdn", A);
    assertThat(engine.decide("smsc", "submit", keys, SimMode.SUCCESS).callback()).isEqualTo(CallbackMode.ONCE);
    engine.add("smsc", MockScenario.of("submit", MockBehaviour.NO_CALLBACK));
    assertThat(engine.decide("smsc", "submit", keys, SimMode.SUCCESS).callback()).isEqualTo(CallbackMode.NONE);
  }
}
