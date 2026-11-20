package mfs.mw.simulators.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import mfs.mw.simulators.dto.BillFetchRequest;
import mfs.mw.simulators.dto.BillPaymentRequest;
import mfs.mw.simulators.dto.Biller;
import mfs.mw.simulators.dto.Biller.Protocol;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.exception.MockAbortException;
import mfs.mw.simulators.mapper.BillAggregatorMapper;
import mfs.mw.simulators.service.mockcontrol.Decision;
import mfs.mw.simulators.service.mockcontrol.MockCalls;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Utility bill aggregator (spec 11.1, 22.2): biller list, bill fetch, payment, status inquiry.
 *
 * <p>Magic values: bill number ending {@code 404} -> fetch refused "bill not found" (3001), ending
 * {@code 000} -> "already paid" (3002), ending {@code 999} -> payment refused by the biller
 * (3003); unknown biller code (3004). Payment amounts follow {@link MagicAmounts}. These are the
 * defaults; mock control (party {@code billagg}, operations {@code billers}, {@code fetch}, {@code pay},
 * {@code status}; keys {@code biller}, {@code billNumber}, {@code txnRef}) overrides them per scope.
 *
 * <p>Protocol guard: each biller is served on one front end ({@link Biller#protocol()}); a biller
 * called on the other one's endpoint is an unknown biller (3004), on fetch and payment alike (a
 * payment for a code that is not in the list at all is still taken, as before). The SOAP front end
 * ({@link BillAggregatorSoapService}) calls {@link #fetch(BillFetchRequest, Protocol)} and {@link
 * #pay(BillPaymentRequest, Protocol)}, so both share the bills, magic values, mock control, callbacks
 * and the payment store.
 *
 * <p>Callback-only mode (party setting {@code callbackOnly}, ticket 22): a payment is applied as usual
 * but answered 202 PENDING (so is every repeat); the outcome goes by callback ({@link
 * CallbackService}), and the status inquiry reports the real one.
 */
@Service
public class BillAggregatorService {

  public static final String PARTNER = "billagg";

  /** The billers, from the seed file {@code seed/v1/billers.csv} ({@link SeedFiles}, ticket 103). */
  public static final List<Biller> BILLERS = SeedFiles.billers(SeedFiles.VERSION);

  private static final Logger log = LoggerFactory.getLogger(BillAggregatorService.class);

  private final MockControl control;
  private final BillPaymentLedger ledger;
  private final BillAggregatorMapper mapper;
  private final CallbackService callbacks;
  private final AggregatorBillLedger bills;

  public BillAggregatorService(
      MockControl control, BillPaymentLedger ledger, BillAggregatorMapper mapper, CallbackService callbacks,
      AggregatorBillLedger bills) {
    this.control = control;
    this.ledger = ledger;
    this.mapper = mapper;
    this.callbacks = callbacks;
    this.bills = bills;
  }

  /**
   * A seeded bill (ticket 114, {@code seed/v1/bills/billagg.csv}) answers with its own amount and state:
   * PAID is refused 3002; an unseeded bill number keeps the computed amount and the magic values.
   */
  private SimReply seededOrDue(BillFetchRequest req) {
    return bills.bill(AggregatorBillLedger.BILLAGG, req.billerCode(), req.billNumber())
        .map(b -> b.paid()
            ? new SimReply(422, mapper.refused(req, BillAggregatorMapper.BILL_ALREADY_PAID, message(BillAggregatorMapper.BILL_ALREADY_PAID)))
            : new SimReply(200, mapper.seeded(req, b.amount().toPlainString(), b.dueDate(), b.billPeriod())))
        .orElseGet(() -> new SimReply(200, mapper.due(req)));
  }

  public SimReply billers() {
    Decision d = control.decide(PARTNER, "billers", Map.of(), SimMode.SUCCESS);
    return MockCalls.answer(
        d,
        PARTNER,
        () -> new SimReply(200, BILLERS),
        () -> new SimReply(503, mapper.unavailable("billers")),
        () -> new SimReply(500, mapper.internalError("billers")),
        code -> new SimReply(422, mapper.refused("billers", code)));
  }

  /** The REST bill fetch. */
  public SimReply fetch(BillFetchRequest req) {
    return fetch(req, Protocol.REST);
  }

  /** A bill fetch over {@code protocol}'s front end: a biller of the other protocol is unknown (3004). */
  public SimReply fetch(BillFetchRequest req, Protocol protocol) {
    String magicCode = req.billNumber().endsWith("404")
        ? BillAggregatorMapper.BILL_NOT_FOUND
        : req.billNumber().endsWith("000") ? BillAggregatorMapper.BILL_ALREADY_PAID : null;
    Decision d = control.decide(
        PARTNER,
        "fetch",
        Map.of("biller", req.billerCode(), "billNumber", req.billNumber()),
        magicCode == null ? SimMode.SUCCESS : SimMode.BUSINESS_FAILURE);
    boolean unknownBiller =
        BILLERS.stream().noneMatch(b -> b.code().equals(req.billerCode()) && b.protocol() == protocol);
    return MockCalls.answer(
        d,
        PARTNER,
        () -> unknownBiller
            ? new SimReply(422, mapper.refused(req, BillAggregatorMapper.UNKNOWN_BILLER, "Unknown biller"))
            : seededOrDue(req),
        () -> new SimReply(503, mapper.unavailable(req)),
        () -> new SimReply(500, mapper.refused(req, "5000", "Internal error")),
        code -> {
          if (unknownBiller) {
            return new SimReply(422, mapper.refused(req, BillAggregatorMapper.UNKNOWN_BILLER, "Unknown biller"));
          }
          String c = code != null ? code : magicCode != null ? magicCode : BillAggregatorMapper.BILL_NOT_FOUND;
          return new SimReply(422, mapper.refused(req, c, message(c)));
        });
  }

  /** The REST payment. */
  public SimReply pay(BillPaymentRequest req) {
    return pay(req, Protocol.REST);
  }

  /**
   * A payment over {@code protocol}'s front end, one per {@code transactionId} in the shared store: a
   * biller of the other protocol is refused 3004 (not applied).
   */
  public SimReply pay(BillPaymentRequest req, Protocol protocol) {
    Decision d = control.decide(
        PARTNER,
        "pay",
        Map.of("biller", req.billerCode(), "billNumber", req.billNumber(), "txnRef", req.transactionId()),
        magic(req));
    Optional<SimReply> closed =
        MockCalls.admit(d, PARTNER, () -> new SimReply(503, mapper.unavailable(req.transactionId())));
    if (closed.isPresent()) {
      return closed.get();
    }
    if (BILLERS.stream().anyMatch(b -> b.code().equals(req.billerCode()) && b.protocol() != protocol)) {
      return new SimReply(422, mapper.unknownBiller(req.transactionId()));
    }
    var existing = ledger.find(req.transactionId());
    if (existing.isPresent()) {
      return reply(existing.get(), d);
    }
    boolean callbackOnly = control.settings(PARTNER).callbackOnly();
    var seeded = bills.bill(AggregatorBillLedger.BILLAGG, req.billerCode(), req.billNumber());
    BillPaymentRecord candidate = seeded.isPresent() && seeded.get().paid() && d.mode() == SimMode.SUCCESS
        ? mapper.alreadyPaid(req, ledger.nextRef(), callbackOnly)
        : mapper.toRecord(req, d.mode(), ledger.nextRef(), callbackOnly).withCode(d.code());
    BillPaymentRecord record = ledger.putIfAbsent(candidate);
    if (record != candidate) {
      return reply(record, d); // a concurrent first payment won
    }
    if (seeded.isPresent() && "SUCCESS".equals(record.status())) {
      bills.mark(AggregatorBillLedger.BILLAGG, req.billerCode(), req.billNumber(), "PAID"); // a paid bill stays paid
    }
    log.atInfo()
        .addKeyValue("event", "sim.billagg.payment.applied")
        .addKeyValue("partnerId", PARTNER)
        .log("payment {} mode {} status {} ({})", record.transactionId(), record.mode(), record.status(), d.source());
    callbacks.afterPayment(record, d);
    if (record.mode() == SimMode.TIMEOUT && !record.callbackOnly()) {
      MockCalls.pause(d.holdMs());
    }
    if (record.mode() == SimMode.CONNECTION_RESET) {
      throw new MockAbortException(PARTNER);
    }
    return reply(record, d);
  }

  /** Status inquiry: the real outcome of an earlier payment, whatever the caller saw. */
  public SimReply status(String transactionId) {
    Decision d = control.decide(PARTNER, "status", Map.of("txnRef", transactionId), SimMode.SUCCESS);
    return MockCalls.answer(
        d,
        PARTNER,
        () -> ledger.find(transactionId)
            .map(r -> new SimReply(200, mapper.toResponse(r)))
            .orElseGet(() -> new SimReply(404, mapper.notFound(transactionId))),
        () -> new SimReply(503, mapper.unavailable(transactionId)),
        () -> new SimReply(500, mapper.internalError(transactionId)),
        code -> new SimReply(422, mapper.refused(transactionId, code)));
  }

  private static String message(String code) {
    return switch (code) {
      case BillAggregatorMapper.BILL_NOT_FOUND -> "Bill not found";
      case BillAggregatorMapper.BILL_ALREADY_PAID -> "Bill already paid";
      default -> "Refused";
    };
  }

  private static SimMode magic(BillPaymentRequest req) {
    if (req.billNumber().endsWith("999")) {
      return SimMode.BUSINESS_FAILURE;
    }
    return MagicAmounts.mode(req.amount());
  }

  private SimReply reply(BillPaymentRecord r, Decision d) {
    if (r.callbackOnly()) {
      return new SimReply(202, mapper.pending(r));
    }
    return switch (r.mode()) {
      case BUSINESS_FAILURE, UNKNOWN_CODE -> new SimReply(422, mapper.toResponse(r));
      case SERVER_ERROR_AFTER_SEND -> new SimReply(d.statusOr(500), mapper.internalError(r.transactionId()));
      case MALFORMED ->
          new SimReply(200, "{\"transactionId\":\"" + r.transactionId() + "\",\"status\":\"SUC");
      case SUCCESS, TIMEOUT, PARTIAL_SUCCESS, CONNECTION_RESET -> new SimReply(200, mapper.toResponse(r));
    };
  }
}
