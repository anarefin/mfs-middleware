package mfs.mw.simulators.service.mockcontrol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;
import mfs.mw.simulators.config.SimulatorProperties;
import mfs.mw.simulators.dto.CallbackMode;
import mfs.mw.simulators.dto.MockBehaviour;
import mfs.mw.simulators.dto.MockBehaviour.Dimension;
import mfs.mw.simulators.dto.MockScenario;
import mfs.mw.simulators.dto.MockSettings;
import mfs.mw.simulators.dto.MockSwitch;
import mfs.mw.simulators.dto.SimMode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * The one mock-control engine of mw-simulators (ADR D33, FRS MCT-01..03, MCK-03..06). A call is decided
 * per dimension (outcome, delay, callback; {@link MockBehaviour.Dimension}): of the scenarios that match
 * its operation and keys, the most specific wins: next-N scoped, then per key, then per operation, then
 * party-wide; among equals the latest. Without one the outcome is the call's magic value (MCK-03), and
 * SUCCESS when it has none. A next-N scenario counts down only when it decided a call and goes at zero.
 * A DOWN or MAINTENANCE switch closes the gate before any scenario is looked at (none is consumed).
 * In memory; a restart or {@link #clear} resets it.
 */
@Service
@Profile(MockControl.PROFILE)
public class ScenarioEngine implements MockControl {

  private final PartySwitch switches;
  private final SimulatorProperties props;
  private final RandomGenerator random;
  private final Map<String, List<MockScenario>> scenarios = new HashMap<>();
  private final AtomicLong ids = new AtomicLong();

  public ScenarioEngine(PartySwitch switches, SimulatorProperties props, RandomGenerator random) {
    this.switches = switches;
    this.props = props;
    this.random = random;
  }

  /** Adds a scenario; answers it with its id and, for next-N, the calls left. */
  public synchronized MockScenario add(String party, MockScenario s) {
    MockParties.check(party, s);
    MockScenario stored = s.withIdAndRemaining("S-" + ids.incrementAndGet(), s.nextN());
    scenarios.computeIfAbsent(party, k -> new ArrayList<>()).add(stored);
    return stored;
  }

  public synchronized List<MockScenario> list(String party) {
    MockParties.require(party);
    return List.copyOf(scenarios.getOrDefault(party, List.of()));
  }

  public synchronized boolean remove(String party, String id) {
    MockParties.require(party);
    return scenarios.getOrDefault(party, new ArrayList<>()).removeIf(s -> s.id().equals(id));
  }

  public synchronized void clear(String party) {
    MockParties.require(party);
    scenarios.remove(party);
  }

  @Override
  public MockSettings settings(String party) {
    return switches.settings(party);
  }

  @Override
  public synchronized Decision decide(String party, String operation, Map<String, String> keys, SimMode magic) {
    MockParties.require(party);
    MockSwitch sw = switches.get(party);
    MockSettings settings = switches.settings(party);
    long latency = sw.latencyMinMs() + (sw.latencyMaxMs() > sw.latencyMinMs()
        ? random.nextLong(sw.latencyMaxMs() - sw.latencyMinMs() + 1) : 0);
    long hold = settings.timeoutDelayMs() != null ? settings.timeoutDelayMs() : props.timeoutDelay().toMillis();
    boolean hit = sw.ratio() >= 1.0 || random.nextDouble() < sw.ratio();
    if (hit && (sw.state() == MockSwitch.State.DOWN || sw.state() == MockSwitch.State.MAINTENANCE)) {
      Decision.Gate gate = sw.state() == MockSwitch.State.DOWN ? Decision.Gate.DOWN : Decision.Gate.MAINTENANCE;
      return new Decision(gate, magic, null, null, hold, latency, CallbackMode.NONE, 0, "switch");
    }
    Optional<MockScenario> outcome = pick(party, operation, keys, Dimension.OUTCOME);
    Optional<MockScenario> delay = pick(party, operation, keys, Dimension.DELAY);
    Optional<MockScenario> callback = pick(party, operation, keys, Dimension.CALLBACK);

    SimMode mode = outcome.map(s -> mode(s.behaviour())).orElse(magic);
    String source = outcome.map(s -> "scenario:" + s.id()).orElse(magic == SimMode.SUCCESS ? "default" : "magic");
    String code = outcome.map(MockScenario::code).orElse(null);
    Integer status = outcome.map(MockScenario::httpStatus).orElse(null);
    if (outcome.isPresent() && outcome.get().behaviour() == MockBehaviour.TIMEOUT && outcome.get().delayMs() != null) {
      hold = outcome.get().delayMs();
    }
    long extra = delay.map(s -> s.delayMs() == null ? 0L : s.delayMs()).orElse(0L);

    CallbackMode cb = CallbackMode.NONE;
    long cbDelay = 0;
    if (MockParties.callsBackInBand(party) || (settings.callbackUrl() != null && !settings.callbackUrl().isBlank())) {
      cb = callback.map(s -> switch (s.behaviour()) {
        case DUPLICATE_CALLBACK -> CallbackMode.DUPLICATE;
        case LATE_CALLBACK -> CallbackMode.LATE;
        case NO_CALLBACK -> CallbackMode.NONE;
        default -> CallbackMode.OUT_OF_ORDER;
      }).orElse(CallbackMode.ONCE);
      cbDelay = callback.map(s -> s.delayMs() == null ? 0L : s.delayMs()).orElse(0L);
    }
    outcome.ifPresent(s -> consume(party, s));
    delay.ifPresent(s -> consume(party, s));
    callback.ifPresent(s -> consume(party, s));
    return new Decision(Decision.Gate.OPEN, mode, code, status, hold, latency + extra, cb, cbDelay, source);
  }

  @Override
  public synchronized Optional<MockScenario> fileFault(String party, String operation, Map<String, String> keys) {
    MockParties.require(party);
    Optional<MockScenario> fault = pick(party, operation, keys, Dimension.FILE);
    fault.ifPresent(s -> consume(party, s));
    return fault;
  }

  private Optional<MockScenario> pick(String party, String operation, Map<String, String> keys, Dimension dimension) {
    List<MockScenario> list = scenarios.getOrDefault(party, List.of());
    MockScenario best = null;
    int bestRank = Integer.MAX_VALUE;
    for (int i = list.size() - 1; i >= 0; i--) { // latest first, so it wins ties
      MockScenario s = list.get(i);
      if (s.behaviour().dimension() != dimension
          || (s.operation() != null && !s.operation().equals(operation))
          || (s.key() != null && !keys.containsValue(s.key()))) {
        continue;
      }
      int rank = s.nextN() != null ? 0 : s.key() != null ? 1 : s.operation() != null ? 2 : 3;
      if (rank < bestRank) {
        best = s;
        bestRank = rank;
      }
    }
    return Optional.ofNullable(best);
  }

  private void consume(String party, MockScenario s) {
    if (s.nextN() == null) {
      return;
    }
    List<MockScenario> list = scenarios.get(party);
    int i = list.indexOf(s);
    if (i < 0) {
      return;
    }
    int left = s.remaining() - 1;
    if (left <= 0) {
      list.remove(i);
    } else {
      list.set(i, s.withIdAndRemaining(s.id(), left));
    }
  }

  private static SimMode mode(MockBehaviour b) {
    return switch (b) {
      case BUSINESS_ERROR -> SimMode.BUSINESS_FAILURE;
      case TIMEOUT -> SimMode.TIMEOUT;
      case HTTP_5XX -> SimMode.SERVER_ERROR_AFTER_SEND;
      case MALFORMED_RESPONSE -> SimMode.MALFORMED;
      case CONNECTION_RESET -> SimMode.CONNECTION_RESET;
      case PARTIAL_SUCCESS -> SimMode.PARTIAL_SUCCESS;
      default -> SimMode.SUCCESS;
    };
  }
}
