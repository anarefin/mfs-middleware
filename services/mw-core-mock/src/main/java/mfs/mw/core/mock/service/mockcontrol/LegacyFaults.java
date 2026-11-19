package mfs.mw.core.mock.service.mockcontrol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import mfs.mw.core.mock.dto.Fault;
import mfs.mw.core.mock.dto.Fault.FaultMode;
import mfs.mw.core.mock.dto.FaultRequest;
import mfs.mw.core.mock.dto.MockBehaviour;
import mfs.mw.core.mock.dto.MockScenario;
import mfs.mw.core.mock.exception.ErrorCodes;
import mfs.mw.core.mock.exception.MwException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * The old fault API {@code /core-mock/v1/faults} as an alias of the engine for one release (ADR D33,
 * ticket 103): each fault becomes one scenario of the core party. {@code commandType} = the operation
 * ({@link MockParties#OPERATION_OF_COMMAND}), {@code txnId} = the key, {@code times} = next-N; {@code
 * REJECT} = BUSINESS_ERROR with {@code reasonCode} (default {@code INJECTED_REJECT}), {@code DELAY} =
 * DELAY {@code delayMs}, {@code DROP_REPLY} = NO_CALLBACK (applied, no result event), {@code
 * DUPLICATE_REPLY} = DUPLICATE_CALLBACK. Outcome and reply are separate dimensions, so a REJECT and a
 * DROP_REPLY together still give a lost refusal. A fault is listed while its scenario lives; {@code
 * DELETE /faults} removes the faults' scenarios only. The first use logs one deprecation line.
 */
@Service
@Profile(MockControl.PROFILE)
public class LegacyFaults {

  private static final Logger log = LoggerFactory.getLogger(LegacyFaults.class);

  private final ScenarioEngine engine;
  private final Map<String, Entry> faults = new LinkedHashMap<>();
  private final AtomicBoolean warned = new AtomicBoolean();

  private record Entry(Fault fault, String scenarioId) {}

  public LegacyFaults(ScenarioEngine engine) {
    this.engine = engine;
  }

  /** Adds a fault; refuses a request that could never fire or is missing its parameter. */
  public synchronized Fault add(FaultRequest r) {
    if (r.mode() == FaultMode.DELAY && r.delayMs() == null) {
      throw new MwException(ErrorCodes.VAL_INVALID, "DELAY needs delayMs");
    }
    String commandType = blankToNull(r.commandType());
    String operation = commandType == null ? null : MockParties.operationOf(commandType);
    if (commandType != null && operation == null) {
      throw new MwException(ErrorCodes.VAL_INVALID, "unknown commandType");
    }
    deprecated();
    String reason = r.mode() == FaultMode.REJECT ? (blank(r.reasonCode()) ? Decision.DEFAULT_REASON : r.reasonCode()) : null;
    Long delay = r.mode() == FaultMode.DELAY ? r.delayMs() : null;
    String txnId = blankToNull(r.txnId());
    MockScenario s = switch (r.mode()) {
      case REJECT -> MockScenario.of(operation, MockBehaviour.BUSINESS_ERROR).withCode(reason);
      case DELAY -> MockScenario.of(operation, MockBehaviour.DELAY).withDelayMs(delay);
      case DROP_REPLY -> MockScenario.of(operation, MockBehaviour.NO_CALLBACK);
      case DUPLICATE_REPLY -> MockScenario.of(operation, MockBehaviour.DUPLICATE_CALLBACK);
    };
    MockScenario stored = engine.add(MockControl.CORE, s.withKey(txnId).withNextN(r.times()));
    Fault f = new Fault(UUID.randomUUID().toString(), r.mode(), commandType, txnId, delay, reason, r.times());
    faults.put(f.id(), new Entry(f, stored.id()));
    return f;
  }

  /** The faults whose scenario still lives, each with the calls it has left. */
  public synchronized List<Fault> list() {
    Map<String, MockScenario> live = new LinkedHashMap<>();
    engine.list(MockControl.CORE).forEach(s -> live.put(s.id(), s));
    faults.values().removeIf(e -> !live.containsKey(e.scenarioId()));
    return faults.values().stream().map(e -> {
      Fault f = e.fault();
      Integer left = Optional.ofNullable(live.get(e.scenarioId())).map(MockScenario::remaining).orElse(null);
      return new Fault(f.id(), f.mode(), f.commandType(), f.txnId(), f.delayMs(), f.reasonCode(), left);
    }).toList();
  }

  /** Removes every fault (their scenarios; other scenarios of the core stay). */
  public synchronized void reset() {
    faults.values().forEach(e -> engine.remove(MockControl.CORE, e.scenarioId()));
    faults.clear();
  }

  /** Removes one fault; false when it does not exist (any more). */
  public synchronized boolean remove(String id) {
    Entry e = faults.remove(id);
    return e != null && engine.remove(MockControl.CORE, e.scenarioId());
  }

  /** Forgets every fault without touching the engine (the engine was cleared). */
  public synchronized void forget() {
    faults.clear();
  }

  private void deprecated() {
    if (warned.compareAndSet(false, true)) {
      log.atWarn()
          .addKeyValue("event", "coremock.faults.deprecated")
          .log("the /core-mock/v1/faults API is deprecated; use /mock-control/core (ADR D33)");
    }
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static String blankToNull(String s) {
    return blank(s) ? null : s;
  }
}
