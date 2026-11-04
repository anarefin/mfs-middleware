package mfs.mw.simulators.service;

import java.util.random.RandomGenerator;
import mfs.mw.simulators.config.SimulatorProperties;
import mfs.mw.simulators.dto.BankDebitRequest;
import mfs.mw.simulators.dto.Scenario;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.mapper.BankDebitMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Generic partner bank for C2W / ADD_MONEY_BANK (spec 11.1, 22.2, ticket 23): debit a linked bank
 * account, outcome by callback, status inquiry.
 *
 * <p>A debit is applied at once and answered 202 PENDING (every repeat too); its outcome goes by
 * callback ({@link CallbackService}, scenario {@code callbackMode}). Magic values: account
 * number ending {@code 999} -> refused by the bank ({@code 4002}, FAILED by callback); amounts
 * follow {@link MagicAmounts}: {@code .13} timeout (debit applied, the 202 held {@code
 * sim.timeout-delay} and no callback, so only the status inquiry learns the outcome), {@code .77}
 * 500 after send (applied, callback as usual), {@code .99} FAILED with the unmapped code {@code
 * 9876}, {@code .55} malformed 202 body (applied, callback as usual). Outage and error rate: 503,
 * not applied.
 */
@Service
public class BankC2wService {

  public static final String PARTNER = "c2wbank";

  private static final Logger log = LoggerFactory.getLogger(BankC2wService.class);

  private final ScenarioStore scenarios;
  private final BankDebitLedger ledger;
  private final BankDebitMapper mapper;
  private final SimulatorProperties props;
  private final RandomGenerator random;
  private final CallbackService callbacks;

  public BankC2wService(
      ScenarioStore scenarios,
      BankDebitLedger ledger,
      BankDebitMapper mapper,
      SimulatorProperties props,
      RandomGenerator random,
      CallbackService callbacks) {
    this.scenarios = scenarios;
    this.ledger = ledger;
    this.mapper = mapper;
    this.props = props;
    this.random = random;
    this.callbacks = callbacks;
  }

  public SimReply debit(BankDebitRequest req) {
    Scenario scenario = scenarios.get(PARTNER);
    sleep(scenario.latencyMs());
    if (scenario.outage() || (scenario.errorRate() > 0 && random.nextDouble() < scenario.errorRate())) {
      return new SimReply(503, mapper.unavailable(req.transactionId()));
    }
    var existing = ledger.find(req.transactionId());
    if (existing.isPresent()) {
      return reply(existing.get());
    }
    BankDebitRecord candidate = mapper.toRecord(req, resolve(req, scenario), ledger.nextRef());
    BankDebitRecord record = ledger.putIfAbsent(candidate);
    if (record != candidate) {
      return reply(record); // a concurrent first debit won
    }
    log.atInfo()
        .addKeyValue("event", "sim.c2wbank.debit.applied")
        .addKeyValue("partnerId", PARTNER)
        .log("debit {} mode {} status {}", record.transactionId(), record.mode(), record.status());
    if (record.mode() == SimMode.TIMEOUT) {
      sleep(scenario.timeoutDelayMs() != null ? scenario.timeoutDelayMs() : props.timeoutDelay().toMillis());
    } else {
      callbacks.afterDebit(record);
    }
    return reply(record);
  }

  /** Status inquiry: the real outcome of an earlier debit, whatever the caller saw. */
  public SimReply status(String transactionId) {
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

  private static SimMode resolve(BankDebitRequest req, Scenario scenario) {
    if (scenario.forceMode() != null) {
      return scenario.forceMode();
    }
    if (req.accountNumber().endsWith("999")) {
      return SimMode.BUSINESS_FAILURE;
    }
    return MagicAmounts.mode(req.amount());
  }

  private SimReply reply(BankDebitRecord r) {
    return switch (r.mode()) {
      case SERVER_ERROR_AFTER_SEND -> new SimReply(500, mapper.internalError(r.transactionId()));
      case MALFORMED -> new SimReply(202, "{\"transactionId\":\"" + r.transactionId() + "\",\"status\":\"PEN");
      default -> new SimReply(202, mapper.pending(r));
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
