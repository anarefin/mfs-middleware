package mfs.mw.orchestrator.service;

import java.util.Optional;
import java.util.UUID;
import mfs.mw.orchestrator.dto.CoreEvent;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.exception.ErrorCodes;
import mfs.mw.orchestrator.mapper.CoreEventMapper;
import mfs.mw.orchestrator.messaging.Inbox;
import mfs.mw.orchestrator.repository.TransactionRepository;
import mfs.mw.orchestrator.service.saga.FlowRegistry;
import mfs.mw.orchestrator.exception.InvalidTransitionException;
import mfs.mw.orchestrator.service.saga.SagaEvent;
import mfs.mw.orchestrator.service.saga.Status;
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

  private final Inbox inbox;
  private final TransactionRepository transactions;
  private final CoreEventMapper mapper;
  private final FlowRegistry flows;
  private final SagaEngine engine;
  private final InquiryService inquiries;

  public CoreEventService(
      Inbox inbox,
      TransactionRepository transactions,
      CoreEventMapper mapper,
      FlowRegistry flows,
      SagaEngine engine,
      InquiryService inquiries) {
    this.inbox = inbox;
    this.transactions = transactions;
    this.mapper = mapper;
    this.flows = flows;
    this.engine = engine;
    this.inquiries = inquiries;
  }

  @Transactional
  public void handle(CoreEvent event) {
    inbox.processOnce(CONSUMER, event.eventId(), () -> apply(event));
  }

  private void apply(CoreEvent event) {
    UUID txnId = UUID.fromString(event.txnId());
    Optional<TransactionRecord> found = transactions.findForUpdate(txnId);
    if (found.isEmpty()) {
      // ReverseTransaction is answered under the REVERSAL request's txnId
      found = transactions.findForUpdateByReversal(txnId);
    }
    if (found.isEmpty()) {
      drop(event, "txn.unknown", "core event for an unknown transaction");
      return;
    }
    TransactionRecord rec = found.get();
    if ("TransactionStatus".equals(event.eventType()) && rec.status() == Status.UNKNOWN) {
      inquiryAnswer(rec, event);
      return;
    }
    boolean needsHold = flows.find(rec.request().txnType()).map(f -> f.needsHold()).orElse(false);
    boolean inbound = flows.find(rec.request().txnType()).map(f -> f.creditsWallet()).orElse(false);
    Optional<SagaEvent> saga = mapper.toSagaEvent(event, needsHold, inbound);
    if (saga.isEmpty()) {
      drop(event, "core.event.unmapped", "core event type not used by any wired flow");
      return;
    }
    ErrorInfo error = errorFor(event, saga.get());
    TransactionRecord current =
        rec.withCore(event.coreRef(), mapper.feeMinor(event)).withHold(mapper.holdId(event));
    try {
      engine.fire(current, saga.get(), error);
    } catch (InvalidTransitionException e) {
      if (saga.get() instanceof SagaEvent.HoldPlaced && gaveUp(rec.status()) && mapper.holdId(event) != null) {
        engine.releaseOrphanHold(rec, mapper.holdId(event));
        return;
      }
      log.atInfo()
          .addKeyValue("event", "saga.event.ignored")
          .addKeyValue("txnId", event.txnId())
          .addKeyValue("status", e.from().name())
          .addKeyValue("eventType", event.eventType())
          .log("core event does not fit the transaction's state; ignored");
    }
  }

  /**
   * The core's reason for a failure event; for the answer to a HOLDING-timeout query, the timeout
   * itself (whether the hold is then released or was never placed, nothing moved).
   */
  private ErrorInfo errorFor(CoreEvent event, SagaEvent saga) {
    if (saga instanceof SagaEvent.HoldQueryResult) {
      return new ErrorInfo(
          ErrorCodes.TMO_CORE_HOLD, "The core did not confirm the hold in time; nothing was moved", null, true);
    }
    return "FAILURE".equals(event.outcome()) ? mapper.error(event) : null;
  }

  /**
   * The core's answer to the status inquiry of a flow without a partner leg (P2P, spec 7.4). A
   * flow with a partner asks the partner, so a TransactionStatus reaching it in UNKNOWN is the
   * late answer to its HOLDING query and is ignored.
   */
  private void inquiryAnswer(TransactionRecord rec, CoreEvent event) {
    boolean partnerLeg =
        flows.find(rec.request().txnType()).flatMap(f -> f.partnerCall()).isPresent();
    if (partnerLeg) {
      drop(event, "saga.event.ignored", "core status answer for a transaction that asks its partner; ignored");
      return;
    }
    inquiries.coreAnswer(rec.withCore(event.coreRef(), null), mapper.txnStatus(event));
  }

  /** States a transaction ends in without ever using a hold (REJECTED, FAILED). */
  private static boolean gaveUp(Status status) {
    return status == Status.REJECTED || status == Status.FAILED;
  }

  private static void drop(CoreEvent event, String name, String message) {
    log.atInfo()
        .addKeyValue("event", name)
        .addKeyValue("txnId", event.txnId())
        .addKeyValue("eventType", event.eventType())
        .log(message);
  }
}
