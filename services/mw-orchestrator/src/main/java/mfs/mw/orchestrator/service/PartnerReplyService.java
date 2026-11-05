package mfs.mw.orchestrator.service;

import java.util.Optional;
import java.util.UUID;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.dto.PartnerReply;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.mapper.AuditMapper;
import mfs.mw.orchestrator.mapper.PartnerReplyMapper;
import mfs.mw.orchestrator.mapper.PartnerRequestMapper;
import mfs.mw.orchestrator.messaging.Inbox;
import mfs.mw.orchestrator.repository.PartnerCallRepository;
import mfs.mw.orchestrator.repository.TransactionRepository;
import mfs.mw.orchestrator.service.saga.FlowRegistry;
import mfs.mw.orchestrator.exception.InvalidTransitionException;
import mfs.mw.orchestrator.service.saga.Outcome;
import mfs.mw.orchestrator.service.saga.SagaEvent;
import mfs.mw.orchestrator.service.saga.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes partner adapter replies on {@code mw.partner.reply.v1} (spec 2.1 step 5, 7.2):
 * SUCCESS commits the hold, FAILED releases it (the reply's canonical error stays on the
 * transaction into FAILED), UNKNOWN moves to UNKNOWN for status inquiry. Any reply for an UNKNOWN
 * transaction (the STATUS_QUERY answer, or a late PAY reply) goes to {@link InquiryService}. Effectively
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
  private final InquiryService inquiries;
  private final FlowRegistry flows;
  private final PartnerCallRepository partnerCalls;

  public PartnerReplyService(
      Inbox inbox,
      TransactionRepository transactions,
      PartnerReplyMapper mapper,
      SagaEngine engine,
      InquiryService inquiries,
      FlowRegistry flows,
      PartnerCallRepository partnerCalls) {
    this.inbox = inbox;
    this.transactions = transactions;
    this.mapper = mapper;
    this.engine = engine;
    this.inquiries = inquiries;
    this.flows = flows;
    this.partnerCalls = partnerCalls;
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
    Optional<TransactionRecord> found = transactions.findForUpdate(UUID.fromString(reply.txnId()));
    if (found.isEmpty()) {
      drop(reply, "txn.unknown", "partner reply for an unknown transaction");
      return;
    }
    recordAnswer(reply);
    if (found.get().status() == Status.UNKNOWN) {
      inquiries.partnerAnswer(found.get(), reply); // STATUS_QUERY answer or a late PAY reply (7.4)
      return;
    }
    if (PartnerRequestMapper.STATUS_QUERY.equals(operation(reply))) {
      lateInquiryAnswer(found.get(), reply);
      return;
    }
    Optional<SagaEvent> saga = mapper.toSagaEvent(reply, found.get().holdsFunds(flows));
    if (saga.isEmpty()) {
      drop(reply, "partner.reply.pending", "partner reply without a final outcome; nothing changes");
      return;
    }
    ErrorInfo error = saga.get() instanceof SagaEvent.PartnerFailed ? mapper.error(reply) : null;
    TransactionRecord rec = found.get().withPartnerRef(reply.payload().partnerRef());
    try {
      engine.fire(rec, saga.get(), error);
    } catch (InvalidTransitionException e) {
      if (PartnerReplyMapper.outcome(reply).map(o -> o.contradicts(e.from())).orElse(false)) {
        conflict(found.get(), reply, "partner reply contradicts the transaction's state; state kept, recorded for operations");
        return;
      }
      log.atInfo()
          .addKeyValue("event", "saga.event.ignored")
          .addKeyValue("txnId", reply.txnId())
          .addKeyValue("partnerId", reply.partnerId())
          .addKeyValue("status", e.from().name())
          .addKeyValue("outcome", reply.payload().outcome())
          .log("partner reply does not fit the transaction's state; ignored");
    }
  }

  /** The answer on the saga timeline (ticket 35), whatever the state machine makes of it. */
  private void recordAnswer(PartnerReply reply) {
    PartnerReply.Payload p = reply.payload();
    if (p == null || reply.repliedAt() == null) {
      return;
    }
    partnerCalls.answered(reply.requestId(), p.outcome(), p.partnerRef(), p.errorCode(), reply.repliedAt());
  }

  /**
   * A STATUS_QUERY answer after the transaction left UNKNOWN changes nothing. One that contradicts
   * how it was resolved (SUCCESS for a released hold, FAILED for a committed one) is logged as
   * {@code txn.outcome.conflict} for operations and reconciliation.
   */
  private static void lateInquiryAnswer(TransactionRecord rec, PartnerReply reply) {
    if (PartnerReplyMapper.outcome(reply).map(o -> o.contradicts(rec.status())).orElse(false)) {
      conflict(rec, reply, "status inquiry answer contradicts the transaction's resolution");
      return;
    }
    log.atInfo()
        .addKeyValue("event", "saga.inquiry.late_answer")
        .addKeyValue("txnId", reply.txnId())
        .addKeyValue("partnerId", reply.partnerId())
        .addKeyValue("status", rec.status().name())
        .addKeyValue("outcome", outcome(reply))
        .log("status inquiry answer after the transaction left UNKNOWN; ignored");
  }

  /**
   * A definite partner answer that contradicts where the transaction stands ({@link
   * Outcome#contradicts}: a late PAY SUCCESS after MANUAL_REVIEW or a refund, a refusal after a
   * payment): no state change, ERROR {@code txn.outcome.conflict} for operations and
   * reconciliation, as for a late callback.
   */
  private static void conflict(TransactionRecord rec, PartnerReply reply, String message) {
    log.atError()
        .addKeyValue("event", AuditMapper.OUTCOME_CONFLICT)
        .addKeyValue("txnId", reply.txnId())
        .addKeyValue("partnerId", reply.partnerId())
        .addKeyValue("status", rec.status().name())
        .addKeyValue("operation", operation(reply))
        .addKeyValue("outcome", outcome(reply))
        .log(message);
  }

  private static String outcome(PartnerReply reply) {
    return reply.payload() == null ? null : reply.payload().outcome();
  }

  private static String operation(PartnerReply reply) {
    return reply.payload() == null ? null : reply.payload().operation();
  }

  private static void drop(PartnerReply reply, String name, String message) {
    log.atInfo()
        .addKeyValue("event", name)
        .addKeyValue("txnId", reply.txnId())
        .addKeyValue("partnerId", reply.partnerId())
        .log(message);
  }
}
