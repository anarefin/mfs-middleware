package mfs.mw.simulators.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import mfs.mw.simulators.config.SimulatorProperties;
import mfs.mw.simulators.dto.OcsMode;
import mfs.mw.simulators.dto.Scenario;
import mfs.mw.simulators.exception.MwException;
import org.junit.jupiter.api.Test;

class ScenarioStoreTest {

  private final ScenarioStore store =
      new ScenarioStore(new SimulatorProperties(Duration.ofMillis(10), 100, List.of("ocs")));

  @Test
  void startsWithTheDefaultScenario() {
    assertThat(store.get("ocs")).isEqualTo(Scenario.DEFAULT);
  }

  @Test
  void setReplacesAndResetRestores() {
    Scenario s = Scenario.DEFAULT.withForceMode(OcsMode.TIMEOUT);
    store.set("ocs", s);
    assertThat(store.get("ocs")).isEqualTo(s);
    store.reset("ocs");
    assertThat(store.get("ocs")).isEqualTo(Scenario.DEFAULT);
  }

  @Test
  void unknownPartnerIsRejected() {
    assertThatThrownBy(() -> store.get("nope"))
        .isInstanceOf(MwException.class)
        .hasMessageContaining("nope");
    assertThatThrownBy(() -> store.set("nope", Scenario.DEFAULT)).isInstanceOf(MwException.class);
  }

  @Test
  void knowsItsPartners() {
    assertThat(store.isPartner("ocs")).isTrue();
    assertThat(store.isPartner("x")).isFalse();
  }
}
