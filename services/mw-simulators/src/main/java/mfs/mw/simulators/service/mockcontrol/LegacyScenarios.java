package mfs.mw.simulators.service.mockcontrol;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import mfs.mw.simulators.dto.MerchantIpnScenario;
import mfs.mw.simulators.dto.MockBehaviour;
import mfs.mw.simulators.dto.MockScenario;
import mfs.mw.simulators.dto.MockSettings;
import mfs.mw.simulators.dto.MockSwitch;
import mfs.mw.simulators.dto.Scenario;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.exception.ErrorCodes;
import mfs.mw.simulators.exception.MwException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The old per-simulator control bodies ({@code /sim/{partner}/scenario}, {@code /sim/merchant/ipn/scenario})
 * as aliases of the engine for one release (ADR D33): each body replaces the party's scenarios, switch
 * and settings with its translation. {@code forceMode} = a party-wide scenario on the paying operation
 * ({@code UNKNOWN_CODE} = BUSINESS_ERROR {@code 9876}, {@code SERVER_ERROR_AFTER_SEND} = HTTP_5XX);
 * {@code outage} = MAINTENANCE, {@code errorRate} = MAINTENANCE for that share; {@code latencyMs} = SLOW
 * with a fixed latency; {@code callbackMode} DUPLICATE / LATE / OUT_OF_ORDER / NONE = the callback scenarios
 * (NONE = NO_CALLBACK, the URL still serves a replay); {@code callbackUrl}, {@code callbackOnly}, {@code timeoutDelayMs} = the
 * settings. The first use logs one deprecation line.
 */
@Service
@Profile(MockControl.PROFILE)
public class LegacyScenarios {

  private static final Logger log = LoggerFactory.getLogger(LegacyScenarios.class);
  private static final Map<String, String> PAYING_OPERATION = Map.of("ocs", "topup", "billagg", "pay", "c2wbank", "debit");
  private static final String MERCHANT = "merchant";

  private final ScenarioEngine engine;
  private final PartySwitch switches;
  private final Map<String, Scenario> last = new ConcurrentHashMap<>();
  private final AtomicBoolean warned = new AtomicBoolean();

  public LegacyScenarios(ScenarioEngine engine, PartySwitch switches) {
    this.engine = engine;
    this.switches = switches;
  }

  /** The old {@code POST /sim/{partner}/scenario}: replaces the party's control state with {@code s}. */
  public synchronized void set(String partner, Scenario s) {
    String op = require(partner);
    deprecated();
    reset(partner);
    last.put(partner, s);
    if (s.forceMode() != null) {
      engine.add(partner, forced(op, s.forceMode()));
    }
    switch (s.callbackMode()) {
      case DUPLICATE -> engine.add(partner, MockScenario.of(null, MockBehaviour.DUPLICATE_CALLBACK));
      case LATE -> engine.add(partner, MockScenario.of(null, MockBehaviour.LATE_CALLBACK).withDelayMs(s.callbackDelayMs()));
      case OUT_OF_ORDER -> engine.add(partner, MockScenario.of(null, MockBehaviour.OUT_OF_ORDER_CALLBACK));
      case NONE -> engine.add(partner, MockScenario.of(null, MockBehaviour.NO_CALLBACK));
      default -> { }
    }
    MockSwitch.State state = s.outage() || s.errorRate() > 0
        ? MockSwitch.State.MAINTENANCE
        : s.latencyMs() > 0 ? MockSwitch.State.SLOW : MockSwitch.State.UP;
    switches.set(partner, new MockSwitch(state, s.latencyMs(), s.latencyMs(), s.outage() ? 1.0 : s.errorRate()));
    switches.settings(partner, new MockSettings(s.callbackUrl(), s.callbackOnly(), s.timeoutDelayMs()));
  }

  /** The old {@code GET /sim/{partner}/scenario}: the last body set, or the default. */
  public Scenario get(String partner) {
    require(partner);
    return last.getOrDefault(partner, Scenario.DEFAULT);
  }

  /** The old {@code DELETE /sim/{partner}/scenario}: scenarios, switch and settings back to the defaults. */
  public synchronized void reset(String partner) {
    require(partner);
    forget(partner);
  }

  /** Any mock party's scenarios, switch and settings back to the defaults (also what this alias remembers). */
  public synchronized void forget(String party) {
    last.remove(party);
    engine.clear(party);
    switches.reset(party);
  }

  /** The old {@code POST /sim/merchant/ipn/scenario}: the next {@code failNext} IPNs answer {@code failStatus}. */
  public synchronized void merchant(MerchantIpnScenario s) {
    deprecated();
    engine.clear(MERCHANT);
    if (s.failNext() > 0) {
      int status = s.failStatusOrDefault();
      MockBehaviour b = status >= 500 ? MockBehaviour.HTTP_5XX : MockBehaviour.BUSINESS_ERROR;
      engine.add(MERCHANT, MockScenario.of("ipn", b).withHttpStatus(status).withKey(s.ipnId()).withNextN(s.failNext()));
    }
  }

  private static MockScenario forced(String op, SimMode mode) {
    return switch (mode) {
      case BUSINESS_FAILURE -> MockScenario.of(op, MockBehaviour.BUSINESS_ERROR);
      case UNKNOWN_CODE -> MockScenario.of(op, MockBehaviour.BUSINESS_ERROR).withCode("9876");
      case TIMEOUT -> MockScenario.of(op, MockBehaviour.TIMEOUT);
      case SERVER_ERROR_AFTER_SEND -> MockScenario.of(op, MockBehaviour.HTTP_5XX);
      case MALFORMED -> MockScenario.of(op, MockBehaviour.MALFORMED_RESPONSE);
      case CONNECTION_RESET -> MockScenario.of(op, MockBehaviour.CONNECTION_RESET);
      case PARTIAL_SUCCESS -> MockScenario.of(op, MockBehaviour.PARTIAL_SUCCESS);
      case SUCCESS -> MockScenario.of(op, MockBehaviour.SUCCESS);
    };
  }

  private static String require(String partner) {
    String op = PAYING_OPERATION.get(partner);
    if (op == null) {
      throw new MwException(ErrorCodes.VAL_NOT_FOUND, "No simulator named " + partner).withStatus(HttpStatus.NOT_FOUND);
    }
    return op;
  }

  private void deprecated() {
    if (warned.compareAndSet(false, true)) {
      log.atWarn()
          .addKeyValue("event", "sim.control.deprecated")
          .log("the /sim/{partner}/scenario control API is deprecated; use /mock-control/{party} (ADR D33)");
    }
  }
}
