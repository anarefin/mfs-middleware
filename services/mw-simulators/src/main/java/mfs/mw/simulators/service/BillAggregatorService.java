package mfs.mw.simulators.service;

import java.util.List;
import java.util.random.RandomGenerator;
import mfs.mw.simulators.config.SimulatorProperties;
import mfs.mw.simulators.dto.BillFetchRequest;
import mfs.mw.simulators.dto.BillPaymentRequest;
import mfs.mw.simulators.dto.Biller;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.dto.Scenario;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.mapper.BillAggregatorMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Utility bill aggregator (spec 11.1, 22.2): biller list, bill fetch, payment, status inquiry.
 *
 * <p>Magic values: bill number ending {@code 404} -> fetch refused "bill not found" (3001), ending
 * {@code 000} -> "already paid" (3002), ending {@code 999} -> payment refused by the biller
 * (3003); unknown biller code (3004). Payment amounts follow {@link MagicAmounts}. A forced mode
 * applies to payments only.
 *
 * <p>Callback-only mode (scenario {@code callbackOnly}, ticket 22): a payment is applied as usual
 * but answered 202 PENDING (so is every repeat); the outcome goes by callback ({@link
 * CallbackService}, scenario {@code callbackMode}), and the status inquiry reports the real one.
 */
@Service
public class BillAggregatorService {

  public static final String PARTNER = "billagg";

  public static final List<Biller> BILLERS =
      List.of(
          new Biller("DESCO", "Dhaka Electric Supply Company", "ELECTRICITY"),
          new Biller("DPDC", "Dhaka Power Distribution Company", "ELECTRICITY"),
          new Biller("WASA", "Dhaka WASA", "WATER"),
          new Biller("TITAS", "Titas Gas", "GAS"));

  private static final Logger log = LoggerFactory.getLogger(BillAggregatorService.class);

  private final ScenarioStore scenarios;
  private final BillPaymentLedger ledger;
  private final BillAggregatorMapper mapper;
  private final SimulatorProperties props;
  private final RandomGenerator random;
  private final CallbackService callbacks;

  public BillAggregatorService(
      ScenarioStore scenarios,
      BillPaymentLedger ledger,
      BillAggregatorMapper mapper,
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

  public List<Biller> billers() {
    sleep(scenarios.get(PARTNER).latencyMs());
    return BILLERS;
  }

  public SimReply fetch(BillFetchRequest req) {
    Scenario scenario = scenarios.get(PARTNER);
    sleep(scenario.latencyMs());
    if (unavailable(scenario)) {
      return new SimReply(503, mapper.unavailable(req));
    }
    if (BILLERS.stream().noneMatch(b -> b.code().equals(req.billerCode()))) {
      return new SimReply(
          422, mapper.refused(req, BillAggregatorMapper.UNKNOWN_BILLER, "Unknown biller"));
    }
    if (req.billNumber().endsWith("404")) {
      return new SimReply(
          422, mapper.refused(req, BillAggregatorMapper.BILL_NOT_FOUND, "Bill not found"));
    }
    if (req.billNumber().endsWith("000")) {
      return new SimReply(
          422, mapper.refused(req, BillAggregatorMapper.BILL_ALREADY_PAID, "Bill already paid"));
    }
    return new SimReply(200, mapper.due(req));
  }

  public SimReply pay(BillPaymentRequest req) {
    Scenario scenario = scenarios.get(PARTNER);
    sleep(scenario.latencyMs());
    if (unavailable(scenario)) {
      return new SimReply(503, mapper.unavailable(req.transactionId()));
    }
    var existing = ledger.find(req.transactionId());
    if (existing.isPresent()) {
      return reply(existing.get());
    }
    SimMode mode = resolve(req, scenario);
    BillPaymentRecord candidate = mapper.toRecord(req, mode, ledger.nextRef(), scenario.callbackOnly());
    BillPaymentRecord record = ledger.putIfAbsent(candidate);
    if (record != candidate) {
      return reply(record); // a concurrent first payment won
    }
    log.atInfo()
        .addKeyValue("event", "sim.billagg.payment.applied")
        .addKeyValue("partnerId", PARTNER)
        .log("payment {} mode {} status {}", record.transactionId(), record.mode(), record.status());
    callbacks.afterPayment(record);
    if (record.mode() == SimMode.TIMEOUT && !record.callbackOnly()) {
      sleep(
          scenario.timeoutDelayMs() != null
              ? scenario.timeoutDelayMs()
              : props.timeoutDelay().toMillis());
    }
    return reply(record);
  }

  /** Status inquiry: the real outcome of an earlier payment, whatever the caller saw. */
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

  private boolean unavailable(Scenario scenario) {
    return scenario.outage()
        || (scenario.errorRate() > 0 && random.nextDouble() < scenario.errorRate());
  }

  private static SimMode resolve(BillPaymentRequest req, Scenario scenario) {
    if (scenario.forceMode() != null) {
      return scenario.forceMode();
    }
    if (req.billNumber().endsWith("999")) {
      return SimMode.BUSINESS_FAILURE;
    }
    return MagicAmounts.mode(req.amount());
  }

  private SimReply reply(BillPaymentRecord r) {
    if (r.callbackOnly()) {
      return new SimReply(202, mapper.pending(r));
    }
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
