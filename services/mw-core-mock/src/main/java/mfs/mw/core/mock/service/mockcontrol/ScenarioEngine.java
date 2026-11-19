package mfs.mw.core.mock.service.mockcontrol;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import mfs.mw.core.mock.config.MockControlProperties;
import mfs.mw.core.mock.dto.MockBehaviour;
import mfs.mw.core.mock.dto.MockBehaviour.Dimension;
import mfs.mw.core.mock.dto.MockMode;
import mfs.mw.core.mock.dto.MockParty;
import mfs.mw.core.mock.dto.MockScenario;
import mfs.mw.core.mock.dto.MockSettings;
import mfs.mw.core.mock.dto.MockSwitch;
import mfs.mw.core.mock.dto.ReplyMode;
import mfs.mw.core.mock.exception.ErrorCodes;
import mfs.mw.core.mock.exception.MwException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * The mock-control engine of mw-core-mock (ADR D33, FRS MCT-01..03, MCK-04 core part, ticket 103),
 * copied from mw-simulators' {@code service/mockcontrol/ScenarioEngine} (module isolation). A call is
 * decided per dimension (outcome, delay, reply; {@link MockBehaviour.Dimension}): of the scenarios that
 * match its operation and keys, the most specific wins: next-N scoped, then per key, then per operation,
 * then party-wide; among equals the latest. Without one the ledger rules decide (the core has no magic
 * values). A next-N scenario counts down only when it decided a call and goes at zero. A DOWN or
 * MAINTENANCE switch closes the gate before any scenario is looked at (none is consumed). The callback
 * dimension is the command's result event (always on: no callback URL). In memory; a restart or {@link
 * #clear} resets it.
 */
@Service
@Profile(MockControl.PROFILE)
public class ScenarioEngine implements MockControl {

  private final PartySwitch switches;
  private final Journal journal;
  private final MockControlProperties props;
  private final RandomGenerator random;
  private final Clock clock;
  private final Map<String, List<MockScenario>> scenarios = new HashMap<>();
  private final AtomicLong ids = new AtomicLong();

  public ScenarioEngine(
      PartySwitch switches, Journal journal, MockControlProperties props, RandomGenerator random, Clock clock) {
    this.switches = switches;
    this.journal = journal;
    this.props = props;
    this.random = random;
    this.clock = clock;
  }

  /** Adds a scenario; answers it with its id and, for next-N, the calls left. */
  public synchronized MockScenario add(String party, MockScenario s) {
    MockParty p = MockParties.require(party);
    if (s.operation() != null && !p.operations().contains(s.operation())) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Party " + party + " has no operation " + s.operation()
          + "; it has " + p.operations());
    }
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

  /**
   * The switch of the core for one REST call ({@code filter/MockControlFilter}): DOWN or MAINTENANCE
   * (for {@code ratio} of the calls) or OPEN, with the latency the switch adds. Rolled once per call.
   */
  public Decision admit() {
    MockSwitch sw = switches.get(CORE);
    long latency = latency(sw);
    Decision.Gate gate = gate(sw);
    return new Decision(gate, MockMode.SUCCESS, null, null, hold(), latency, ReplyMode.ONCE, 0,
        gate == Decision.Gate.OPEN ? "default" : "switch");
  }

  @Override
  public synchronized Decision decide(String operation, Map<String, String> keys) {
    boolean rest = RestCall.admitted();
    long latency = 0;
    if (!rest) {
      MockSwitch sw = switches.get(CORE);
      latency = latency(sw);
      Decision.Gate gate = gate(sw);
      if (gate != Decision.Gate.OPEN) {
        return new Decision(gate, MockMode.SUCCESS, null, null, hold(), latency, ReplyMode.ONCE, 0, "switch");
      }
    }
    Optional<MockScenario> outcome = pick(operation, keys, Dimension.OUTCOME);
    Optional<MockScenario> delay = pick(operation, keys, Dimension.DELAY);
    Optional<MockScenario> reply = pick(operation, keys, Dimension.CALLBACK);

    MockMode mode = outcome.map(s -> mode(s.behaviour())).orElse(MockMode.SUCCESS);
    String source = outcome.map(s -> "scenario:" + s.id()).orElse("default");
    String code = outcome.map(MockScenario::code).orElse(null);
    Integer status = outcome.map(MockScenario::httpStatus).orElse(null);
    long hold = hold();
    if (outcome.isPresent() && outcome.get().behaviour() == MockBehaviour.TIMEOUT && outcome.get().delayMs() != null) {
      hold = outcome.get().delayMs();
    }
    long extra = delay.map(s -> s.delayMs() == null ? 0L : s.delayMs()).orElse(0L);
    ReplyMode rm = reply.map(s -> switch (s.behaviour()) {
      case DUPLICATE_CALLBACK -> ReplyMode.DUPLICATE;
      case LATE_CALLBACK -> ReplyMode.LATE;
      case NO_CALLBACK -> ReplyMode.NONE;
      default -> ReplyMode.ONCE;
    }).orElse(ReplyMode.ONCE);
    long replyDelay = reply.map(s -> s.delayMs() == null ? 0L : s.delayMs()).orElse(0L);
    outcome.ifPresent(this::consume);
    delay.ifPresent(this::consume);
    reply.ifPresent(this::consume);
    Decision d = new Decision(Decision.Gate.OPEN, mode, code, status, hold, latency + extra, rm, replyDelay, source);
    RestCall.current().ifPresent(c -> c.decided(d));
    return d;
  }

  @Override
  public void record(String operation, String commandType, String txnId, Map<String, String> keys) {
    String body = new LinkedHashMap<>(keys).entrySet().stream()
        .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("\n"));
    journal.record(CORE, "COMMAND", "/" + operation + "/" + commandType, null,
        txnId == null ? Map.of() : Map.of("x-txn-id", txnId), body, clock.instant());
  }

  private long hold() {
    MockSettings settings = switches.settings(CORE);
    return settings.timeoutDelayMs() != null ? settings.timeoutDelayMs() : props.timeoutDelay().toMillis();
  }

  private long latency(MockSwitch sw) {
    return sw.latencyMinMs() + (sw.latencyMaxMs() > sw.latencyMinMs()
        ? random.nextLong(sw.latencyMaxMs() - sw.latencyMinMs() + 1) : 0);
  }

  private Decision.Gate gate(MockSwitch sw) {
    boolean hit = sw.ratio() >= 1.0 || random.nextDouble() < sw.ratio();
    if (hit && sw.state() == MockSwitch.State.DOWN) {
      return Decision.Gate.DOWN;
    }
    if (hit && sw.state() == MockSwitch.State.MAINTENANCE) {
      return Decision.Gate.MAINTENANCE;
    }
    return Decision.Gate.OPEN;
  }

  private Optional<MockScenario> pick(String operation, Map<String, String> keys, Dimension dimension) {
    List<MockScenario> list = scenarios.getOrDefault(CORE, List.of());
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

  private void consume(MockScenario s) {
    if (s.nextN() == null) {
      return;
    }
    List<MockScenario> list = scenarios.get(CORE);
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

  private static MockMode mode(MockBehaviour b) {
    return switch (b) {
      case BUSINESS_ERROR -> MockMode.BUSINESS_FAILURE;
      case TIMEOUT -> MockMode.TIMEOUT;
      case HTTP_5XX -> MockMode.SERVER_ERROR_AFTER_SEND;
      case MALFORMED_RESPONSE -> MockMode.MALFORMED;
      case CONNECTION_RESET -> MockMode.CONNECTION_RESET;
      case PARTIAL_SUCCESS -> MockMode.PARTIAL_SUCCESS;
      default -> MockMode.SUCCESS;
    };
  }
}
