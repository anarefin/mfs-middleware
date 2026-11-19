package mfs.mw.simulators.service;

import java.util.Map;
import java.util.Optional;
import mfs.mw.simulators.dto.BankDebitRequest;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.exception.MockAbortException;
import mfs.mw.simulators.mapper.BankDebitMapper;
import mfs.mw.simulators.service.mockcontrol.Decision;
import mfs.mw.simulators.service.mockcontrol.MockCalls;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Generic partner bank for C2W / ADD_MONEY_BANK (spec 11.1, 22.2, ticket 23; mock party {@code c2wbank},
 * operations {@code debit} and {@code status}, keys {@code account} and {@code txnRef}): debit a linked
 * bank account, outcome by callback, status inquiry.
 *
 * <p>A debit is applied at once and answered 202 PENDING (every repeat too); its outcome goes by
 * callback ({@link CallbackService}). Magic values (the defaults mock control overrides): account
 * number ending {@code 999} -> refused by the bank ({@code 4002}, FAILED by callback); amounts
 * follow {@link MagicAmounts}: {@code .13} timeout (debit applied, the 202 held and no callback, so
 * only the status inquiry learns the outcome), {@code .77} 500 after send (applied, callback as usual),
 * {@code .99} FAILED with the unmapped code {@code 9876}, {@code .55} malformed 202 body (applied,
 * callback as usual). MAINTENANCE: 503, not applied.
 */
@Service
public class BankC2wService {

  public static final String PARTNER = "c2wbank";

  private static final Logger log = LoggerFactory.getLogger(BankC2wService.class);

  private final MockControl control;
  private final BankDebitLedger ledger;
  private final BankDebitMapper mapper;
  private final CallbackService callbacks;

  public BankC2wService(MockControl control, BankDebitLedger ledger, BankDebitMapper mapper, CallbackService callbacks) {
    this.control = control;
    this.ledger = ledger;
    this.mapper = mapper;
    this.callbacks = callbacks;
  }

  public SimReply debit(BankDebitRequest req) {
    Decision d = control.decide(
        PARTNER, "debit", Map.of("account", req.accountNumber(), "txnRef", req.transactionId()), magic(req));
    Optional<SimReply> closed =
        MockCalls.admit(d, PARTNER, () -> new SimReply(503, mapper.unavailable(req.transactionId())));
    if (closed.isPresent()) {
      return closed.get();
    }
    var existing = ledger.find(req.transactionId());
    if (existing.isPresent()) {
      return reply(existing.get(), d);
    }
    BankDebitRecord candidate = mapper.toRecord(req, d.mode(), ledger.nextRef()).withCode(d.code());
    BankDebitRecord record = ledger.putIfAbsent(candidate);
    if (record != candidate) {
      return reply(record, d); // a concurrent first debit won
    }
    log.atInfo()
        .addKeyValue("event", "sim.c2wbank.debit.applied")
        .addKeyValue("partnerId", PARTNER)
        .log("debit {} mode {} status {} ({})", record.transactionId(), record.mode(), record.status(), d.source());
    if (record.mode() == SimMode.TIMEOUT) {
      MockCalls.pause(d.holdMs());
    } else {
      callbacks.afterDebit(record, d);
    }
    if (record.mode() == SimMode.CONNECTION_RESET) {
      throw new MockAbortException(PARTNER);
    }
    return reply(record, d);
  }

  /** Status inquiry: the real outcome of an earlier debit, whatever the caller saw. */
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

  private static SimMode magic(BankDebitRequest req) {
    if (req.accountNumber().endsWith("999")) {
      return SimMode.BUSINESS_FAILURE;
    }
    return MagicAmounts.mode(req.amount());
  }

  private SimReply reply(BankDebitRecord r, Decision d) {
    return switch (r.mode()) {
      case SERVER_ERROR_AFTER_SEND -> new SimReply(d.statusOr(500), mapper.internalError(r.transactionId()));
      case MALFORMED -> new SimReply(202, "{\"transactionId\":\"" + r.transactionId() + "\",\"status\":\"PEN");
      default -> new SimReply(202, mapper.pending(r));
    };
  }
}
