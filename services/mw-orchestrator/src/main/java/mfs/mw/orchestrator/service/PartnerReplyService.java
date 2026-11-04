package mfs.mw.orchestrator.service;

import java.util.Optional;
import java.util.UUID;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.dto.PartnerReply;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.mapper.PartnerReplyMapper;
import mfs.mw.orchestrator.messaging.Inbox;
import mfs.mw.orchestrator.repository.TransactionRepository;
import mfs.mw.orchestrator.service.saga.InvalidTransitionException;
import mfs.mw.orchestrator.service.saga.SagaEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes partner adapter replies on {@code mw.partner.reply.v1} (spec 2.1 step 5, 7.2):
 * SUCCESS commits the hold, FAILED releases it (the reply's canonical error stays on the
 * transaction into FAILED), UNKNOWN moves to UNKNOWN for status inquiry (ticket 18). Effectively
 * once: the inbox drops a redelivered {@code requestId}+outcome, and a reply the state does not
 * expect (late after a timeout, for a finished transaction) is logged and dropped.
 */
@Service
public class PartnerReplyService {

  private static final Logger log = LoggerFactory.getLogger(PartnerReplyService.class);

  static final String CONSUMER = "orchestrator.partner-replies";

  private final Inbox inbox;
  private final TransactionRepository transactions;
  private final PartnerReplyMapper mapper;
  private final SagaEngine engine;

  public PartnerReplyService(
      Inbox inbox, TransactionRepository transactions, PartnerReplyMapper mapper, SagaEngine engine) {
    this.inbox = inbox;
    this.transactions = transactions;
    this.mapper = mapper;
    this.engine = engine;
  }

  /**
   * The inbox key is {@code requestId:outcome}: one request has one final answer, but a PENDING
   * answer may precede it.
   */
  @Transactional
  public void handle(PartnerReply reply) {
    String outcome = reply.payload() == null ? "" : String.valueOf(reply.payload().outcome());
    inbox.processOnce(CONSUMER, reply.requestId() + ":" + outcome, () -> apply(reply));
  }

  private void apply(PartnerReply reply) {
    Optional<SagaEvent> saga = mapper.toSagaEvent(reply);
    if (saga.isEmpty()) {
      drop(reply, "partner.reply.pending", "partner reply without a final outcome; nothing changes");
      return;
    }
    Optional<TransactionRecord> found = transactions.findForUpdate(UUID.fromString(reply.txnId()));
    if (found.isEmpty()) {
      drop(reply, "txn.unknown", "partner reply for an unknown transaction");
      return;
    }
    ErrorInfo error = saga.get() instanceof SagaEvent.PartnerFailed ? mapper.error(reply) : null;
    TransactionRecord rec = found.get().withPartnerRef(reply.payload().partnerRef());
    try {
      engine.fire(rec, saga.get(), error);
    } catch (InvalidTransitionException e) {
      log.atInfo()
          .addKeyValue("event", "saga.event.ignored")
          .addKeyValue("txnId", reply.txnId())
          .addKeyValue("partnerId", reply.partnerId())
          .addKeyValue("status", e.from().name())
          .addKeyValue("outcome", reply.payload().outcome())
          .log("partner reply does not fit the transaction's state; ignored");
    }
  }

  private static void drop(PartnerReply reply, String name, String message) {
    log.atInfo()
        .addKeyValue("event", name)
        .addKeyValue("txnId", reply.txnId())
        .addKeyValue("partnerId", reply.partnerId())
        .log(message);
  }
}
