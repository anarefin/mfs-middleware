package mfs.mw.orchestrator.service;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.orchestrator.dto.CoreReply;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.exception.ErrorCodes;
import mfs.mw.orchestrator.exception.InvalidTransitionException;
import mfs.mw.orchestrator.mapper.CoreCommandMapper;
import mfs.mw.orchestrator.mapper.CoreReplyMapper;
import mfs.mw.orchestrator.repository.CoreCallRepository;
import mfs.mw.orchestrator.repository.TransactionRepository;
import mfs.mw.orchestrator.service.saga.CoreTxnStatus;
import mfs.mw.orchestrator.service.saga.FlowRegistry;
import mfs.mw.orchestrator.service.saga.PendingLeg;
import mfs.mw.orchestrator.service.saga.RefundFlow;
import mfs.mw.orchestrator.service.saga.SagaEvent;
import mfs.mw.orchestrator.service.saga.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies the core's REST answer to a saga step (ADR D31, ticket 95a; spec 7.1, PLT-021) in a
 * transaction of its own, under the transaction's row lock: the answer is recorded on the saga
 * timeline ({@code core_call}) and re-enters {@link SagaEngine} as a saga event. The state machine
 * refuses an answer the state no longer expects (a late answer after the deadline moved on), which is
 * logged and dropped. An ambiguous outcome changes nothing: the step's deadline, armed when the step
 * was entered, settles it (VALIDATING to FAILED, HOLDING to a WAL-13 query, POSTING to UNKNOWN and
 * WAL-13 inquiries, COMMITTING / RELEASING / REVERSING resends with the same txnId).
 */
@Service
public class CoreReplyService {

  private static final Logger log = LoggerFactory.getLogger(CoreReplyService.class);

  private final TransactionRepository transactions;
  private final CoreReplyMapper mapper;
  private final FlowRegistry flows;
  private final SagaEngine engine;
  private final InquiryService inquiries;
  private final CoreCommandMapper commands;
  private final SagaAlerts alerts;
  private final CoreCallRepository coreCalls;
  private final Clock clock;

  @SuppressWarnings("java:S107")
  public CoreReplyService(
      TransactionRepository transactions,
      CoreReplyMapper mapper,
      FlowRegistry flows,
      SagaEngine engine,
      InquiryService inquiries,
      CoreCommandMapper commands,
      SagaAlerts alerts,
      CoreCallRepository coreCalls,
      Clock clock) {
    this.transactions = transactions;
    this.mapper = mapper;
    this.flows = flows;
    this.engine = engine;
    this.inquiries = inquiries;
    this.commands = commands;
    this.alerts = alerts;
    this.coreCalls = coreCalls;
    this.clock = clock;
  }

  /** Applies {@code reply}; its own transaction (the call ran after the step's transaction committed). */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void handle(CoreReply reply) {
    if (reply.kind() == CoreReply.Kind.AMBIGUOUS) {
      return; // logged by the client; the deadline settles it
    }
    UUID txnId = UUID.fromString(reply.txnId());
    Optional<TransactionRecord> found = transactions.findForUpdate(txnId);
    if (found.isEmpty()) {
      // a reversal is answered under the REVERSAL request's txnId
      found = transactions.findForUpdateByReversal(txnId);
    }
    if (found.isEmpty()) {
      drop(reply, "txn.unknown", "core answer for an unknown transaction");
      return;
    }
    TransactionRecord rec = found.get();
    String result = mapper.result(reply);
    if (reply.commandId() != null) {
      // the saga timeline shows every answer, also one the state machine then ignores (B13)
      coreCalls.answered(reply.commandId(), result, mapper.outcome(reply), mapper.reasonCode(reply), clock.instant());
    }
    if (rec.pendingLeg() == PendingLeg.HOLD_RELEASE
        && ("HoldReleased".equals(result) || "ReleaseFailed".equals(result))) {
      compensatingRelease(rec, reply, "HoldReleased".equals(result));
      return;
    }
    boolean status = "TransactionStatus".equals(result);
    if (status && commands.isHoldCheck(reply.commandId())) {
      holdCheckAnswer(rec, reply);
      return;
    }
    if (status && commands.isReversalQuery(reply.commandId())) {
      reversalAnswer(rec, reply);
      return;
    }
    if (status && commands.isCreditCheck(reply.commandId())) {
      creditCheckAnswer(rec, reply);
      return;
    }
    if (status && rec.status() == Status.UNKNOWN) {
      inquiryAnswer(rec, reply);
      return;
    }
    boolean needsHold = flows.find(rec.request().txnType()).map(f -> f.needsHold()).orElse(false);
    boolean inbound = flows.find(rec.request().txnType()).map(f -> f.creditsWallet()).orElse(false);
    Optional<SagaEvent> saga = mapper.toSagaEvent(reply, needsHold, inbound);
    if (saga.isPresent() && saga.get() instanceof SagaEvent.CommitRefused && compensate(rec, reply)) {
      saga = Optional.of(new SagaEvent.CommitRefused(true));
    }
    if (saga.isEmpty()) {
      drop(reply, "core.reply.unmapped", "core answer not used by any wired flow");
      return;
    }
    if (isRefund(rec)) {
      saga = saga.map(CoreReplyService::refundEvent);
    }
    ErrorInfo error = errorFor(reply, saga.get());
    TransactionRecord current = rec.withCore(mapper.coreRef(reply), mapper.feeMinor(reply)).withHold(mapper.holdId(reply));
    try {
      // a BALANCE answer goes to the COMPLETED status only, never onto the record (ADR D26)
      engine.fire(current, saga.get(), error, mapper.balance(reply).orElse(null));
      if (saga.get() instanceof SagaEvent.CommitRefused || saga.get() instanceof SagaEvent.ReleaseRefused) {
        alerts.refused(rec.status(), rec.txnId(), error);
      }
    } catch (InvalidTransitionException e) {
      if (saga.get() instanceof SagaEvent.HoldPlaced && gaveUp(rec.status()) && mapper.holdId(reply) != null) {
        engine.releaseOrphanHold(rec, mapper.holdId(reply));
        return;
      }
      log.atInfo()
          .addKeyValue("event", "saga.event.ignored")
          .addKeyValue("txnId", reply.txnId())
          .addKeyValue("status", e.from().name())
          .addKeyValue("eventType", result)
          .log("core answer does not fit the transaction's state; ignored");
    }
  }

  /** The refusal's error; for WAL-13 after a HOLDING timeout, the timeout itself (nothing moved). */
  private ErrorInfo errorFor(CoreReply reply, SagaEvent saga) {
    if (saga instanceof SagaEvent.HoldQueryResult) {
      return new ErrorInfo(
          ErrorCodes.TMO_CORE_HOLD, "The core did not confirm the hold in time; nothing was moved", null, true);
    }
    return reply.kind() == CoreReply.Kind.REFUSED ? mapper.error(reply) : null;
  }

  /**
   * WAL-13 for the status inquiry of a flow without a partner leg (P2P, spec 7.4). A flow with a
   * partner asks the partner, so a status reaching it in UNKNOWN is the late answer to its HOLDING
   * query and is ignored.
   */
  private void inquiryAnswer(TransactionRecord rec, CoreReply reply) {
    boolean partnerLeg = flows.find(rec.request().txnType()).flatMap(f -> f.partnerCall()).isPresent();
    if (partnerLeg) {
      drop(reply, "saga.event.ignored", "core status answer for a transaction that asks its partner; ignored");
      return;
    }
    inquiries.coreAnswer(rec.withCore(mapper.coreRef(reply), null), mapper.txnStatus(reply));
  }

  /**
   * The saga's own commit was refused on a flow with a partner leg (ticket 96b). An operator
   * round ({@code :manual-n}) stays MANUAL_REVIEW: the operator decides whether to try again.
   */
  private boolean compensate(TransactionRecord rec, CoreReply reply) {
    String commandId = reply.commandId();
    boolean operatorRound = commandId != null && commandId.contains(":manual-");
    return !operatorRound && flows.find(rec.request().txnType()).flatMap(f -> f.partnerCall()).isPresent();
  }

  /**
   * After the partner confirmed REVERSE: HELD still has a hold to release, RELEASED / NOT_FOUND /
   * REJECTED / REVERSED has nothing left to release, and any other status (including no answer's
   * deadline, which does not come here) is unclear and goes to MANUAL_REVIEW.
   */
  private void holdCheckAnswer(TransactionRecord rec, CoreReply reply) {
    SagaEvent.HoldCheckResult.Hold hold =
        switch (CoreTxnStatus.parse(mapper.txnStatus(reply)).orElse(null)) {
          case HELD -> SagaEvent.HoldCheckResult.Hold.EXISTS;
          case RELEASED, NOT_FOUND, REJECTED, REVERSED -> SagaEvent.HoldCheckResult.Hold.GONE;
          case null, default -> SagaEvent.HoldCheckResult.Hold.UNCLEAR;
        };
    try {
      engine.fire(
          rec.withHold(mapper.holdId(reply)), new SagaEvent.HoldCheckResult(hold),
          hold == SagaEvent.HoldCheckResult.Hold.UNCLEAR ? HOLD_UNCLEAR : null);
    } catch (InvalidTransitionException e) {
      drop(reply, "saga.event.ignored", "hold check answer does not fit the transaction's state; ignored");
    }
  }

  /** ReleaseHold of the hold that remained after the partner confirmed REVERSE. */
  private void compensatingRelease(TransactionRecord rec, CoreReply reply, boolean released) {
    try {
      engine.fire(
          rec,
          released ? new SagaEvent.CompensatingReleaseAcknowledged() : new SagaEvent.CompensatingReleaseRefused(),
          released ? null : mapper.error(reply));
    } catch (InvalidTransitionException e) {
      drop(reply, "saga.event.ignored", "compensating release answer does not fit the transaction's state; ignored");
    }
  }

  static final ErrorInfo HOLD_UNCLEAR =
      new ErrorInfo(
          ErrorCodes.TMO_HOLD_UNCLEAR,
          "The core's status of the hold fits no outcome of the partner reversal; operations will review it",
          null,
          false);

  /**
   * WAL-13 asked once every reversal resend had expired (S1): REVERSED ends it REVERSED, anything else
   * hands it to operations ({@code MW-TMO-020}). Outside REVERSING / MANUAL_REVIEW it is stale.
   */
  private void reversalAnswer(TransactionRecord rec, CoreReply reply) {
    boolean reversed = "REVERSED".equals(mapper.txnStatus(reply));
    SagaEvent event = new SagaEvent.ReversalQueryResult(reversed);
    if (reversed && isRefund(rec)) {
      // asked under the refund's own txnId: the core booked it (ticket 116b)
      event = new SagaEvent.RefundConfirmed();
    }
    try {
      engine.fire(rec, event, reversed ? null : REVERSAL_NOT_APPLIED);
    } catch (InvalidTransitionException e) {
      drop(reply, "saga.event.ignored", "reversal query answer does not fit the transaction's state; ignored");
    }
  }

  /**
   * WAL-13 of a forced credit's check (ticket 37): CREDITED / POSTED completes it with no new credit,
   * NOT_FOUND / VALIDATED / REJECTED lets the fresh-round credit go, anything else hands it back to
   * operations. Only the answer to the latest check counts.
   */
  private void creditCheckAnswer(TransactionRecord rec, CoreReply reply) {
    if (!coreCalls.latestCreditCheck(rec.txnId()).map(reply.commandId()::equals).orElse(false)) {
      drop(reply, "saga.event.ignored", "answer to an older credit check; ignored");
      return;
    }
    SagaEvent.CreditCheckResult.Credit credit =
        switch (CoreTxnStatus.parse(mapper.txnStatus(reply)).orElse(null)) {
          case CREDITED, POSTED -> SagaEvent.CreditCheckResult.Credit.CREDITED;
          case NOT_FOUND, VALIDATED, REJECTED -> SagaEvent.CreditCheckResult.Credit.NOT_CREDITED;
          case null, default -> SagaEvent.CreditCheckResult.Credit.CONFLICT;
        };
    try {
      engine.fire(
          rec.withCore(mapper.coreRef(reply), null), new SagaEvent.CreditCheckResult(credit),
          credit == SagaEvent.CreditCheckResult.Credit.CONFLICT ? CREDIT_CHECK_CONFLICT : null);
    } catch (InvalidTransitionException e) {
      drop(reply, "saga.event.ignored", "credit check answer does not fit the transaction's state; ignored");
    }
  }

  static final ErrorInfo CREDIT_CHECK_CONFLICT =
      new ErrorInfo(
          ErrorCodes.TMO_INQUIRY_CONFLICT,
          "The core's status of the transaction fits no outcome of the forced credit; operations will review it",
          null,
          false);

  static final ErrorInfo REVERSAL_NOT_APPLIED =
      new ErrorInfo(
          ErrorCodes.TMO_CORE_REVERSE,
          "The core did not confirm the reversal in time; operations will review it",
          null,
          false);

  /** A merchant refund record (ticket 116b): the reversal's answer is about the record itself. */
  private static boolean isRefund(TransactionRecord rec) {
    return RefundFlow.TXN_TYPE.equals(rec.request().txnType());
  }

  /** The core's reversal answer on a refund record: COMPLETED when booked, REJECTED when refused. */
  private static SagaEvent refundEvent(SagaEvent event) {
    return switch (event) {
      case SagaEvent.ReversalAcknowledged e -> new SagaEvent.RefundConfirmed();
      case SagaEvent.ReversalFailed e -> new SagaEvent.RefundRefused();
      default -> event;
    };
  }

  /** States a transaction ends in without ever using a hold (REJECTED, FAILED). */
  private static boolean gaveUp(Status status) {
    return status == Status.REJECTED || status == Status.FAILED;
  }

  private static void drop(CoreReply reply, String name, String message) {
    log.atInfo()
        .addKeyValue("event", name)
        .addKeyValue("txnId", reply.txnId())
        .addKeyValue("commandType", reply.commandType())
        .log(message);
  }
}
