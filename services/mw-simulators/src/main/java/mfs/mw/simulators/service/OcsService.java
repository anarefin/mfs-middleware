package mfs.mw.simulators.service;

import java.util.Optional;
import java.util.random.RandomGenerator;
import mfs.mw.simulators.config.SimulatorProperties;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.dto.OcsTopupRequest;
import mfs.mw.simulators.dto.Scenario;
import mfs.mw.simulators.mapper.OcsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Prepaid top-up OCS: applies top-ups, answers per scenario, keeps the real outcome for inquiries. */
@Service
public class OcsService {

  private static final Logger log = LoggerFactory.getLogger(OcsService.class);
  private static final String PARTNER = "ocs";

  private final ScenarioStore scenarios;
  private final OcsLedger ledger;
  private final OcsMapper mapper;
  private final CallbackService callbacks;
  private final SimulatorProperties props;
  private final RandomGenerator random;
  private final OcsScenarioResolver resolver = new OcsScenarioResolver();

  public OcsService(
      ScenarioStore scenarios,
      OcsLedger ledger,
      OcsMapper mapper,
      CallbackService callbacks,
      SimulatorProperties props,
      RandomGenerator random) {
    this.scenarios = scenarios;
    this.ledger = ledger;
    this.mapper = mapper;
    this.callbacks = callbacks;
    this.props = props;
    this.random = random;
  }

  public SimReply topup(OcsTopupRequest req) {
    Scenario scenario = scenarios.get(PARTNER);
    sleep(scenario.latencyMs());
    if (scenario.outage()
        || (scenario.errorRate() > 0 && random.nextDouble() < scenario.errorRate())) {
      return new SimReply(503, mapper.unavailable(req.transactionId()));
    }
    Optional<TopupRecord> existing = ledger.find(req.transactionId());
    if (existing.isPresent()) {
      return reply(existing.get());
    }
    SimMode mode = resolver.resolve(req, scenario);
    TopupRecord record = mapper.toRecord(req, mode, ledger.nextRef());
    ledger.put(record);
    log.atInfo()
        .addKeyValue("event", "sim.ocs.topup.applied")
        .addKeyValue("partnerId", PARTNER)
        .log("top-up {} mode {} status {}", record.transactionId(), mode, record.status());
    callbacks.afterTopup(record);
    if (mode == SimMode.TIMEOUT) {
      sleep(
          scenario.timeoutDelayMs() != null
              ? scenario.timeoutDelayMs()
              : props.timeoutDelay().toMillis());
    }
    return reply(record);
  }

  /** Status inquiry: the real outcome of an earlier top-up, whatever the caller saw. */
  public SimReply statusReply(String transactionId) {
    Scenario scenario = scenarios.get(PARTNER);
    sleep(scenario.latencyMs());
    if (scenario.outage()) {
      return new SimReply(503, mapper.unavailable(transactionId));
    }
    return ledger
        .find(transactionId)
        .map(r -> new SimReply(200, mapper.toResponse(r)))
        .orElseGet(() -> new SimReply(404, mapper.notFound(transactionId)));
  }

  private SimReply reply(TopupRecord r) {
    return switch (r.mode()) {
      case BUSINESS_FAILURE, UNKNOWN_CODE -> new SimReply(422, mapper.toResponse(r));
      case SERVER_ERROR_AFTER_SEND -> new SimReply(500, mapper.internalError(r.transactionId()));
      case MALFORMED ->
          new SimReply(200, "{\"transactionId\":\"" + r.transactionId() + "\",\"status\":\"SUC");
      case SUCCESS, TIMEOUT -> new SimReply(200, mapper.toResponse(r));
    };
  }

  private static void sleep(long ms) {
    if (ms <= 0) {
      return;
    }
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
