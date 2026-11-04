package mfs.mw.simulators.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import mfs.mw.simulators.client.CallbackClient;
import mfs.mw.simulators.config.SimulatorProperties;
import mfs.mw.simulators.dto.CallbackMode;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.dto.OcsTopupRequest;
import mfs.mw.simulators.dto.Scenario;
import mfs.mw.simulators.exception.MwException;
import mfs.mw.simulators.mapper.OcsMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CallbackServiceTest {

  private final ScenarioStore scenarios =
      new ScenarioStore(new SimulatorProperties(Duration.ofMillis(10), 100, List.of("ocs")));
  private final OcsLedger ledger = new OcsLedger();
  private final List<String> sent = new ArrayList<>();
  private final CallbackClient client =
      new CallbackClient() {
        @Override
        public int post(String url, String json) {
          sent.add(url);
          return 200;
        }
      };
  private final OcsMapper mapper = new OcsMapper();
  private final CallbackService service =
      new CallbackService(ledger, scenarios, client, mapper, JsonMapper.builder().build(), Runnable::run);

  private TopupRecord record() {
    TopupRecord r =
        mapper.toRecord(new OcsTopupRequest("T-1", "01712345678", "10.00"), SimMode.SUCCESS, "R-1");
    ledger.put(r);
    return r;
  }

  @Test
  void replayResendsToTheScenarioUrl() {
    record();
    scenarios.set("ocs", Scenario.DEFAULT.withCallback(CallbackMode.NONE, "http://cb.test/a", 0));
    assertThat(service.replay("ocs", "T-1", null)).isEqualTo(200);
    assertThat(sent).containsExactly("http://cb.test/a");
  }

  @Test
  void replayUrlOverrideWins() {
    record();
    service.replay("ocs", "T-1", "http://other.test/b");
    assertThat(sent).containsExactly("http://other.test/b");
  }

  @Test
  void replayWithoutAnyUrlIsRejected() {
    record();
    assertThatThrownBy(() -> service.replay("ocs", "T-1", null)).isInstanceOf(MwException.class);
  }

  @Test
  void replayOfAnUnknownReferenceIsNotFound() {
    assertThatThrownBy(() -> service.replay("ocs", "T-none", "http://x.test"))
        .isInstanceOf(MwException.class);
  }

  @Test
  void lateCallbackWaitsForItsDelay() {
    TopupRecord r = record();
    scenarios.set("ocs", Scenario.DEFAULT.withCallback(CallbackMode.LATE, "http://cb.test/c", 30));
    long t0 = System.nanoTime();
    service.afterTopup(r);
    assertThat(Duration.ofNanos(System.nanoTime() - t0)).isGreaterThanOrEqualTo(Duration.ofMillis(25));
    assertThat(sent).hasSize(1);
  }

  @Test
  void onceSendsOneCallback() {
    TopupRecord r = record();
    scenarios.set("ocs", Scenario.DEFAULT.withCallback(CallbackMode.ONCE, "http://cb.test/d", 0));
    service.afterTopup(r);
    assertThat(sent).hasSize(1);
  }
}
