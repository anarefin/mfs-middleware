package mfs.mw.simulators.service;

import java.util.Map;
import java.util.Optional;
import mfs.mw.simulators.dto.OcsTopupRequest;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.exception.MockAbortException;
import mfs.mw.simulators.mapper.OcsMapper;
import mfs.mw.simulators.service.mockcontrol.Decision;
import mfs.mw.simulators.service.mockcontrol.MockCalls;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Prepaid top-up OCS (mock party {@code ocs}, operations {@code topup} and {@code status}): applies
 * top-ups as mock control decides (magic values by default), keeps the real outcome for inquiries.
 */
@Service
public class OcsService {

  public static final String PARTY = "ocs";

  private static final Logger log = LoggerFactory.getLogger(OcsService.class);

  private final MockControl control;
  private final OcsLedger ledger;
  private final OcsMapper mapper;
  private final CallbackService callbacks;
  private final OcsScenarioResolver resolver = new OcsScenarioResolver();

  public OcsService(MockControl control, OcsLedger ledger, OcsMapper mapper, CallbackService callbacks) {
    this.control = control;
    this.ledger = ledger;
    this.mapper = mapper;
    this.callbacks = callbacks;
  }

  public SimReply topup(OcsTopupRequest req) {
    Decision d = control.decide(
        PARTY, "topup", Map.of("msisdn", req.msisdn(), "txnRef", req.transactionId()), resolver.magic(req));
    Optional<SimReply> closed =
        MockCalls.admit(d, PARTY, () -> new SimReply(503, mapper.unavailable(req.transactionId())));
    if (closed.isPresent()) {
      return closed.get();
    }
    Optional<TopupRecord> existing = ledger.find(req.transactionId());
    if (existing.isPresent()) {
      return reply(existing.get(), d);
    }
    TopupRecord record = mapper.toRecord(req, d.mode(), ledger.nextRef()).withCode(d.code());
    ledger.put(record);
    log.atInfo()
        .addKeyValue("event", "sim.ocs.topup.applied")
        .addKeyValue("partnerId", PARTY)
        .log("top-up {} mode {} status {} ({})", record.transactionId(), d.mode(), record.status(), d.source());
    callbacks.afterTopup(record, d);
    if (d.mode() == SimMode.TIMEOUT) {
      MockCalls.pause(d.holdMs());
    }
    if (d.mode() == SimMode.CONNECTION_RESET) {
      throw new MockAbortException(PARTY);
    }
    return reply(record, d);
  }

  /** Status inquiry: the real outcome of an earlier top-up, whatever the caller saw. */
  public SimReply statusReply(String transactionId) {
    Decision d = control.decide(PARTY, "status", Map.of("txnRef", transactionId), SimMode.SUCCESS);
    return MockCalls.answer(
        d,
        PARTY,
        () -> ledger.find(transactionId)
            .map(r -> new SimReply(200, mapper.toResponse(r)))
            .orElseGet(() -> new SimReply(404, mapper.notFound(transactionId))),
        () -> new SimReply(503, mapper.unavailable(transactionId)),
        () -> new SimReply(500, mapper.internalError(transactionId)),
        code -> new SimReply(422, mapper.refused(transactionId, code)));
  }

  private SimReply reply(TopupRecord r, Decision d) {
    return switch (r.mode()) {
      case BUSINESS_FAILURE, UNKNOWN_CODE -> new SimReply(422, mapper.toResponse(r));
      case SERVER_ERROR_AFTER_SEND -> new SimReply(d.statusOr(500), mapper.internalError(r.transactionId()));
      case MALFORMED ->
          new SimReply(200, "{\"transactionId\":\"" + r.transactionId() + "\",\"status\":\"SUC");
      case SUCCESS, TIMEOUT, PARTIAL_SUCCESS, CONNECTION_RESET -> new SimReply(200, mapper.toResponse(r));
    };
  }
}
