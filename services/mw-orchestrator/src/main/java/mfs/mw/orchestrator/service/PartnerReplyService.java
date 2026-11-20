package mfs.mw.orchestrator.service;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.orchestrator.dto.AuditEvent;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.exception.ErrorCodes;
import mfs.mw.orchestrator.messaging.EventMetadata;
import mfs.mw.orchestrator.messaging.OutboxWriter;
import mfs.mw.orchestrator.dto.PartnerReply;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.mapper.AuditMapper;
import mfs.mw.orchestrator.mapper.PartnerReplyMapper;
import mfs.mw.orchestrator.mapper.PartnerRequestMapper;
import mfs.mw.orchestrator.messaging.Inbox;
import mfs.mw.orchestrator.repository.PartnerCallRepository;
import mfs.mw.orchestrator.repository.TransactionRepository;
import mfs.mw.orchestrator.service.routing.RoutingCatalog;
import mfs.mw.orchestrator.service.saga.FlowRegistry;
import mfs.mw.orchestrator.service.saga.MerchantPayFlow;
import mfs.mw.orchestrator.service.saga.RemitInFlow;
import mfs.mw.orchestrator.exception.InvalidTransitionException;
import mfs.mw.orchestrator.service.saga.Outcome;
import mfs.mw.orchestrator.service.saga.PendingLeg;
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
  private final AuditMapper audits;
  private final OutboxWriter outbox;
  private final AddMoneyCardService cards;
  private final Clock clock;

  public PartnerReplyService(
      Inbox inbox,
      TransactionRepository transactions,
      PartnerReplyMapper mapper,
      SagaEngine engine,
      InquiryService inquiries,
      FlowRegistry flows,
      PartnerCallRepository partnerCalls,
      AuditMapper audits,
      OutboxWriter outbox,
      AddMoneyCardService cards,
      Clock clock) {
    this.cards = cards;
    this.inbox = inbox;
    this.transactions = transactions;
    this.mapper = mapper;
    this.engine = engine;
    this.inquiries = inquiries;
    this.flows = flows;
    this.partnerCalls = partnerCalls;
    this.audits = audits;
    this.outbox = outbox;
    this.clock = clock;
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
    if (RemitInFlow.TXN_TYPE.equals(found.get().request().txnType())) {
      // ticket 119b: the adapter's answer to an outcome confirmation never moves the remittance
      log.atInfo()
          .addKeyValue("event", "remit.outcome.confirmed")
          .addKeyValue("txnId", reply.txnId())
          .addKeyValue("partnerId", reply.partnerId())
          .addKeyValue("outcome", reply.payload().outcome())
          .log("remittance outcome confirmation answered; the transaction is unchanged");
      return;
    }
    if (MerchantPayFlow.TXN_TYPE.equals(found.get().request().txnType())
        && MerchantPayFlow.MERCHANT.family().equals(reply.family())) {
      merchantAnswer(found.get(), reply);
      return;
    }
    if (AddMoneyCardService.isCard(found.get()) && PartnerRequestMapper.INQUIRY.equals(operation(reply))) {
      cards.sessionAnswer(found.get(), reply); // ticket 118b: CREATE_SESSION is never a payment outcome
      return;
    }
    if (AddMoneyCardService.isCard(found.get()) && cards.captureMismatch(found.get(), reply)) {
      cards.refuseMismatchedCapture(found.get(), reply); // M9 review: never credit a capture of another amount
      return;
    }
    if (PartnerRequestMapper.REVERSE.equals(operation(reply))) {
      applyReverse(found.get(), reply);
      return;
    }
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
    if (saga.get() instanceof SagaEvent.PartnerFailed
        && RoutingCatalog.preSendRefusal(error == null ? null : error.code())
        && engine.failOver(found.get())) {
      return;
    }
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

  /**
   * A REVERSE reply while the pending leg is the partner (ticket 96b, 96c). SUCCESS of a refused
   * commit asks the core about the hold; SUCCESS of a completed top-up or bill payment (the
   * original carries a reversal txn id) sends the core reversal. A definite FAILED of a refused
   * commit ends FAILED and leaves the hold untouched; a definite FAILED of a completed top-up or
   * bill payment ends MANUAL_REVIEW and does not touch the posting. UNKNOWN, PENDING and NOT_FOUND
   * wait for the reversal deadline, which resends the same key. A reply after the leg has moved on
   * changes nothing.
   */
  private void applyReverse(TransactionRecord rec, PartnerReply reply) {
    if (rec.pendingLeg() != PendingLeg.PARTNER_REVERSE) {
      drop(reply, "saga.event.ignored", "partner reversal reply arrived after that leg finished");
      return;
    }
    Optional<Outcome> outcome = PartnerReplyMapper.outcome(reply);
    if (outcome.isEmpty() || outcome.get() == Outcome.UNKNOWN || outcome.get() == Outcome.PENDING
        || outcome.get() == Outcome.NOT_FOUND) {
      drop(reply, "partner.reverse.pending", "partner reversal has no definite answer yet; the deadline resends it");
      return;
    }
    boolean completed = rec.reversalTxnId() != null;
    try {
      if (outcome.get() == Outcome.SUCCESS) {
        engine.fire(rec, completed
            ? new SagaEvent.PartnerReverseConfirmed(SagaEvent.PartnerReverseConfirmed.Follow.CORE)
            : new SagaEvent.PartnerReverseConfirmed(), null);
      } else {
        engine.fire(rec, completed
            ? new SagaEvent.PartnerReverseRefused(SagaEvent.PartnerReverseRefused.End.MANUAL_REVIEW)
            : new SagaEvent.PartnerReverseRefused(), mapper.error(reply));
      }
    } catch (InvalidTransitionException e) {
      log.atInfo()
          .addKeyValue("event", "saga.event.ignored")
          .addKeyValue("txnId", reply.txnId())
          .addKeyValue("status", e.from().name())
          .addKeyValue("outcome", outcome.get().name())
          .log("partner reversal reply does not fit the transaction's state; ignored");
    }
  }

  /**
   * The merchant system's answer for a MERCHANT_PAY (ticket 115b). VALIDATE (INQUIRY) while
   * VALIDATING: SUCCESS asks the core next; FAILED, UNKNOWN or NOT_FOUND end FAILED with the partner's
   * error (nothing has moved, so no inquiry is worth it; the payer may simply try again); PENDING waits
   * for the VALIDATING deadline. NOTIFY (PAY) never touches the transaction: SUCCESS is logged; any
   * other answer is retried with a fresh request id, after a growing backoff ({@code notify-backoff},
   * saga timer {@code MERCHANT_NOTIFY}), until {@code notify-attempts} sends, then the
   * notify is dead-lettered (ERROR {@code merchant.notify.dead_lettered}, audit
   * {@code merchant.notify.failed}). Any other answer is late and only on the timeline.
   */
  private void merchantAnswer(TransactionRecord rec, PartnerReply reply) {
    Optional<Outcome> outcome = PartnerReplyMapper.outcome(reply);
    if (outcome.isEmpty() || outcome.get() == Outcome.PENDING) {
      drop(reply, "partner.reply.pending", "merchant system answer without a final outcome; nothing changes");
      return;
    }
    String op = operation(reply);
    if (PartnerRequestMapper.INQUIRY.equals(op) && rec.status() == Status.VALIDATING) {
      if (outcome.get() == Outcome.SUCCESS) {
        engine.fire(rec, new SagaEvent.MerchantValidated(), null);
      } else {
        ErrorInfo error = mapper.error(reply);
        engine.fire(rec, new SagaEvent.MerchantRejected(), error != null ? error
            : new ErrorInfo(ErrorCodes.TMO_CORE_VALIDATE, "The merchant could not be confirmed", null, true));
      }
      return;
    }
    if (PartnerRequestMapper.PAY.equals(op)) {
      notifyAnswer(rec, reply, outcome.get());
      return;
    }
    drop(reply, "saga.event.ignored", "merchant system answer after that step finished");
  }

  private void notifyAnswer(TransactionRecord rec, PartnerReply reply, Outcome outcome) {
    int sent = engine.merchantNotifyAttempts(rec);
    if (outcome == Outcome.SUCCESS) {
      log.atInfo()
          .addKeyValue("event", "merchant.notify.delivered")
          .addKeyValue("txnId", reply.txnId())
          .addKeyValue("partnerId", reply.partnerId())
          .addKeyValue("attempt", sent)
          .log("merchant system received the payment notify");
      return;
    }
    if (!reply.requestId().equals(PartnerRequestMapper.requestId(reply.txnId(), "NOTIFY:" + sent))) {
      drop(reply, "saga.event.ignored", "answer to an older merchant notify attempt; the latest decides");
      return;
    }
    if (sent < engine.merchantNotifyMaxAttempts()) {
      engine.scheduleMerchantNotify(rec, sent + 1); // M9 review: after a backoff, by the saga timer
      return;
    }
    String errorCode = reply.payload().errorCode();
    log.atError()
        .addKeyValue("event", "merchant.notify.dead_lettered")
        .addKeyValue("txnId", reply.txnId())
        .addKeyValue("partnerId", reply.partnerId())
        .addKeyValue("attempts", sent)
        .addKeyValue("errorCode", errorCode)
        .log("merchant payment notify failed after every attempt; the payment stays COMPLETED");
    AuditEvent e = audits.merchantNotifyFailed(rec, reply.partnerId(), sent, errorCode, clock.instant());
    outbox.append(
        SagaEngine.AUDIT_TOPIC, e.txnId(), new EventMetadata(e.correlationId(), e.txnId(), e.auditId(), 1), e);
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
