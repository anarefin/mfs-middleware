package mfs.mw.orchestrator.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import mfs.mw.orchestrator.config.InquiryProperties;
import mfs.mw.orchestrator.config.OrchestratorProperties;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.dto.PartnerReply;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.exception.ErrorCodes;
import mfs.mw.orchestrator.mapper.PartnerReplyMapper;
import mfs.mw.orchestrator.repository.SagaTimerRepository;
import mfs.mw.orchestrator.repository.TransactionRepository;
import mfs.mw.orchestrator.service.saga.Flow;
import mfs.mw.orchestrator.service.saga.FlowRegistry;
import mfs.mw.orchestrator.service.saga.InquirySchedule;
import mfs.mw.orchestrator.service.saga.SagaEvent;
import mfs.mw.orchestrator.service.saga.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Status inquiry of UNKNOWN outcomes (spec 7.4, FR-07). No transaction stays in UNKNOWN without a
 * scheduled inquiry: entering UNKNOWN arms the first slot ({@link SagaEngine}), each slot sends one
 * inquiry and arms the next ({@link #due}), and the cut-off hands the transaction to an operator
 * (MANUAL_REVIEW, {@code MW-TMO-004}). Answers:
 *
 * <ul>
 *   <li>SUCCESS: commit the hold (TOPUP) or complete (P2P, the core posted it);
 *   <li>FAILED: release the hold with the partner's error, or fail (the core rejected it);
 *   <li>NOT_FOUND: like FAILED ({@code MW-TMO-003}), but only once the original call can no longer
 *       arrive (its deadline plus {@code not-found-grace}); before that, still pending;
 *   <li>PENDING / UNKNOWN / no answer: still pending, the next slot asks again;
 *   <li>an answer that fits no outcome (the core reports a hold for a transfer): MANUAL_REVIEW
 *       ({@code MW-TMO-005}).
 * </ul>
 *
 * Callers hold the transaction's row lock and run inside a database transaction.
 */
@Service
public class InquiryService {

  private static final Logger log = LoggerFactory.getLogger(InquiryService.class);

  private final TransactionRepository transactions;
  private final SagaTimerRepository timers;
  private final SagaEngine engine;
  private final FlowRegistry flows;
  private final PartnerReplyMapper replies;
  private final OrchestratorProperties properties;
  private final InquiryProperties inquiry;
  private final Clock clock;

  public InquiryService(
      TransactionRepository transactions,
      SagaTimerRepository timers,
      SagaEngine engine,
      FlowRegistry flows,
      PartnerReplyMapper replies,
      OrchestratorProperties properties,
      InquiryProperties inquiry,
      Clock clock) {
    this.transactions = transactions;
    this.timers = timers;
    this.engine = engine;
    this.flows = flows;
    this.replies = replies;
    this.properties = properties;
    this.inquiry = inquiry;
    this.clock = clock;
  }

  /**
   * An inquiry slot of an UNKNOWN transaction came due; {@code sent} inquiries went out before.
   * Past the cut-off: MANUAL_REVIEW. Otherwise one more inquiry and the next slot.
   */
  public void due(TransactionRecord rec, int sent) {
    Instant now = clock.instant();
    InquirySchedule schedule = inquiry.scheduleFor(SagaEngine.partnerId(rec));
    Instant since = unknownSince(rec);
    if (schedule.exhausted(since, now)) {
      engine.fire(rec, new SagaEvent.InquiryExhausted(), error(ErrorCodes.TMO_INQUIRY_EXHAUSTED,
          "The outcome is still unknown after every status inquiry; an operator will resolve it", false));
      log.atWarn()
          .addKeyValue("event", "saga.inquiry.exhausted")
          .addKeyValue("txnId", rec.txnId().toString())
          .addKeyValue("partnerId", SagaEngine.partnerId(rec))
          .addKeyValue("inquiries", sent)
          .log("status inquiry cut-off reached; transaction handed to manual review");
      return;
    }
    engine.fire(rec, new SagaEvent.DeadlineExpired(), null);
    timers.armAttempt(rec.txnId(), SagaEngine.INQUIRY_TIMER, schedule.after(sent + 1, since, now), sent + 1);
  }

  /**
   * A partner answer for an UNKNOWN transaction: the STATUS_QUERY reply, or a late PAY reply,
   * which is as definite.
   */
  public void partnerAnswer(TransactionRecord rec, PartnerReply reply) {
    String outcome = reply.payload() == null ? "" : String.valueOf(reply.payload().outcome());
    boolean held = held(rec);
    // a commit step follows a partner success: commit the hold, or credit inbound money
    boolean commits = held || flows.find(rec.request().txnType()).map(Flow::creditsWallet).orElse(false);
    switch (outcome) {
      case "SUCCESS" ->
          resolve(rec.withPartnerRef(reply.payload().partnerRef()), new SagaEvent.InquirySucceeded(commits), null, outcome);
      case "FAILED" -> resolve(rec, new SagaEvent.InquiryFailed(held), replies.error(reply), outcome);
      case "NOT_FOUND" -> {
        Instant lastArrival =
            transactions.enteredAt(rec.txnId(), Status.PARTNER_PENDING).orElse(unknownSince(rec))
                .plus(properties.partnerTimeout());
        notFound(rec, held, lastArrival);
      }
      default -> pending(rec, outcome);
    }
  }

  /**
   * The core's TransactionStatus for an UNKNOWN transaction without a partner leg (P2P: the
   * PostTransfer reply was lost). POSTED / CREDITED: done; REJECTED: failed; NOT_FOUND /
   * VALIDATED: the post was never applied, final once the command has expired.
   */
  public void coreAnswer(TransactionRecord rec, String txnStatus) {
    String s = txnStatus == null ? "" : txnStatus;
    switch (s) {
      case "POSTED", "CREDITED" -> resolve(rec, new SagaEvent.InquirySucceeded(false), null, s);
      case "REJECTED" ->
          resolve(rec, new SagaEvent.InquiryFailed(false), notFoundError("The core refused the transfer; nothing was moved"), s);
      case "NOT_FOUND", "VALIDATED" -> {
        Instant lastArrival =
            transactions.enteredAt(rec.txnId(), Status.POSTING).orElse(unknownSince(rec)).plus(properties.commandTtl());
        notFound(rec, false, lastArrival);
      }
      default -> {
        engine.fire(rec, new SagaEvent.InquiryExhausted(), error(ErrorCodes.TMO_INQUIRY_CONFLICT,
            "The status inquiry answer fits no outcome of the transaction; an operator will resolve it", false));
        log.atError()
            .addKeyValue("event", "saga.inquiry.conflict")
            .addKeyValue("txnId", rec.txnId().toString())
            .addKeyValue("answer", s)
            .log("status inquiry answer contradicts the transaction; handed to manual review");
      }
    }
  }

  /** "Not found" is final only once the original call can no longer arrive (plus the grace). */
  private void notFound(TransactionRecord rec, boolean held, Instant lastArrival) {
    Duration grace = inquiry.notFoundGrace();
    if (clock.instant().isBefore(lastArrival.plus(grace))) {
      pending(rec, "NOT_FOUND");
      return;
    }
    resolve(rec, new SagaEvent.InquiryFailed(held),
        notFoundError("The original request never reached its destination; nothing was moved"), "NOT_FOUND");
  }

  private void resolve(TransactionRecord rec, SagaEvent event, ErrorInfo error, String answer) {
    engine.fire(rec, event, error);
    log.atInfo()
        .addKeyValue("event", "saga.inquiry.resolved")
        .addKeyValue("txnId", rec.txnId().toString())
        .addKeyValue("partnerId", SagaEngine.partnerId(rec))
        .addKeyValue("answer", answer)
        .log("status inquiry resolved an unknown outcome");
  }

  private static void pending(TransactionRecord rec, String answer) {
    log.atInfo()
        .addKeyValue("event", "saga.inquiry.pending")
        .addKeyValue("txnId", rec.txnId().toString())
        .addKeyValue("partnerId", SagaEngine.partnerId(rec))
        .addKeyValue("answer", answer)
        .log("status inquiry gave no final answer; the next slot asks again");
  }

  private boolean held(TransactionRecord rec) {
    return holds(flows, rec);
  }

  /** Whether the transaction's flow holds funds (a refusal then releases them). */
  static boolean holds(FlowRegistry flows, TransactionRecord rec) {
    return flows.find(rec.request().txnType()).map(Flow::needsHold).orElse(rec.holdId() != null);
  }

  private Instant unknownSince(TransactionRecord rec) {
    return transactions.enteredAt(rec.txnId(), Status.UNKNOWN).orElse(rec.updatedAt());
  }

  private static ErrorInfo notFoundError(String message) {
    return error(ErrorCodes.TMO_INQUIRY_NOT_FOUND, message, true);
  }

  private static ErrorInfo error(String code, String message, boolean retryable) {
    return new ErrorInfo(code, message, null, retryable);
  }
}
