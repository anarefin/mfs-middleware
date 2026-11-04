package mfs.mw.orchestrator.service;

import java.util.Optional;
import java.util.UUID;
import mfs.mw.orchestrator.dto.CoreEvent;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.mapper.CoreEventMapper;
import mfs.mw.orchestrator.messaging.Inbox;
import mfs.mw.orchestrator.repository.TransactionRepository;
import mfs.mw.orchestrator.service.saga.FlowRegistry;
import mfs.mw.orchestrator.service.saga.InvalidTransitionException;
import mfs.mw.orchestrator.service.saga.SagaEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes core result events (spec 6.3, 7.1). Effectively once: the inbox drops a redelivered
 * {@code eventId}, and the state machine refuses an event the transaction's state does not expect
 * (a late reply after a timeout, a second event for the same command), which is logged and
 * dropped rather than retried.
 */
@Service
public class CoreEventService {

  private static final Logger log = LoggerFactory.getLogger(CoreEventService.class);

  static final String CONSUMER = "orchestrator.core-events";
  private static final String INVALID_PIN = "MW-CORE-INVALID_PIN";

  private final Inbox inbox;
  private final TransactionRepository transactions;
  private final CoreEventMapper mapper;
  private final FlowRegistry flows;
  private final SagaEngine engine;

  public CoreEventService(
      Inbox inbox,
      TransactionRepository transactions,
      CoreEventMapper mapper,
      FlowRegistry flows,
      SagaEngine engine) {
    this.inbox = inbox;
    this.transactions = transactions;
    this.mapper = mapper;
    this.flows = flows;
    this.engine = engine;
  }

  @Transactional
  public void handle(CoreEvent event) {
    inbox.processOnce(CONSUMER, event.eventId(), () -> apply(event));
  }

  private void apply(CoreEvent event) {
    Optional<TransactionRecord> found = transactions.findForUpdate(UUID.fromString(event.txnId()));
    if (found.isEmpty()) {
      drop(event, "txn.unknown", "core event for an unknown transaction");
      return;
    }
    TransactionRecord rec = found.get();
    boolean needsHold = flows.find(rec.request().txnType()).map(f -> f.needsHold()).orElse(false);
    Optional<SagaEvent> saga = mapper.toSagaEvent(event, needsHold);
    if (saga.isEmpty()) {
      drop(event, "core.event.unmapped", "core event type not used by any wired flow");
      return;
    }
    ErrorInfo error = "FAILURE".equals(event.outcome()) ? mapper.error(event) : null;
    if (error != null && INVALID_PIN.equals(error.code())) {
      flagThirdPartyPin(rec, event);
    }
    try {
      engine.fire(rec.withCore(event.coreRef(), mapper.feeMinor(event)), saga.get(), error);
    } catch (InvalidTransitionException e) {
      log.atInfo()
          .addKeyValue("event", "saga.event.ignored")
          .addKeyValue("txnId", event.txnId())
          .addKeyValue("status", e.from().name())
          .addKeyValue("eventType", event.eventType())
          .log("core event does not fit the transaction's state; ignored");
    }
  }

  /**
   * A wrong PIN for a wallet that is not the initiator's (an agent entering a customer's PIN for
   * CASH_OUT) is a fraud signal: business event {@code txn.pin.failed.third.party}, without any
   * MSISDN. The lockout itself is the core's rule (spec 9.1).
   */
  private static void flagThirdPartyPin(TransactionRecord rec, CoreEvent event) {
    var req = rec.request();
    if (req.initiator() == null || req.debitParty() == null
        || java.util.Objects.equals(req.initiator().id(), req.debitParty().id())) {
      return;
    }
    log.atWarn()
        .addKeyValue("event", "txn.pin.failed.third.party")
        .addKeyValue("txnId", event.txnId())
        .addKeyValue("txnType", req.txnType())
        .addKeyValue("channel", req.channel())
        .addKeyValue("debitWalletType", req.debitParty().walletType())
        .log("wrong PIN for a wallet other than the initiator's");
  }

  private static void drop(CoreEvent event, String name, String message) {
    log.atInfo()
        .addKeyValue("event", name)
        .addKeyValue("txnId", event.txnId())
        .addKeyValue("eventType", event.eventType())
        .log(message);
  }
}
