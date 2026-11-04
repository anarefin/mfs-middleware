package mfs.mw.orchestrator.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import mfs.mw.orchestrator.config.InquiryProperties;
import mfs.mw.orchestrator.config.OrchestratorProperties;
import mfs.mw.orchestrator.dto.AuditEvent;
import mfs.mw.orchestrator.dto.CoreCommand;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.dto.PartnerRequest;
import mfs.mw.orchestrator.dto.StatusMessage;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.mapper.AuditMapper;
import mfs.mw.orchestrator.mapper.CoreCommandMapper;
import mfs.mw.orchestrator.mapper.PartnerRequestMapper;
import mfs.mw.orchestrator.mapper.StatusMapper;
import mfs.mw.orchestrator.messaging.EventMetadata;
import mfs.mw.orchestrator.messaging.MwHeaders;
import mfs.mw.orchestrator.messaging.OutboxWriter;
import mfs.mw.orchestrator.repository.SagaTimerRepository;
import mfs.mw.orchestrator.repository.TransactionRepository;
import mfs.mw.orchestrator.service.saga.Effect;
import mfs.mw.orchestrator.service.saga.Flow;
import mfs.mw.orchestrator.service.saga.FlowRegistry;
import mfs.mw.orchestrator.service.saga.PartnerCall;
import mfs.mw.orchestrator.service.saga.InvalidTransitionException;
import mfs.mw.orchestrator.service.saga.SagaEvent;
import mfs.mw.orchestrator.service.saga.SagaMachine;
import mfs.mw.orchestrator.service.saga.Status;
import mfs.mw.orchestrator.service.saga.Step;
import mfs.mw.orchestrator.service.saga.Transition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Applies one event to one transaction (spec 7.1): runs the pure {@link SagaMachine}, persists the
 * new state, step and deadline, publishes the status change and sends the next command, all
 * through the outbox in the caller's transaction. Callers hold the row lock on the transaction.
 * Deliberately not {@code @Transactional}: an {@link InvalidTransitionException} must not mark the
 * caller's transaction rollback-only, and the outbox refuses to run outside a transaction anyway.
 *
 * <p>Every state change is published here and nowhere else ({@link #created} for the first state,
 * {@link #fire} for every later one), each with its audit event on {@code mw.audit.event.v1}
 * (spec 14.3), so the audit trail has one record per state a transaction passed through.
 */
@Service
public class SagaEngine {

  private static final Logger log = LoggerFactory.getLogger(SagaEngine.class);

  public static final String STATUS_TOPIC = "mw.txn.status.v1";
  public static final String AUDIT_TOPIC = "mw.audit.event.v1";

  private final TransactionRepository transactions;
  private final SagaTimerRepository timers;
  private final OutboxWriter outbox;
  private final StatusMapper statusMapper;
  private final CoreCommandMapper commandMapper;
  private final AuditMapper auditMapper;
  private final PartnerRequestMapper partnerMapper;
  private final FlowRegistry flows;
  private final OrchestratorProperties properties;
  private final InquiryProperties inquiry;
  private final Clock clock;

  public SagaEngine(
      TransactionRepository transactions,
      SagaTimerRepository timers,
      OutboxWriter outbox,
      StatusMapper statusMapper,
      CoreCommandMapper commandMapper,
      AuditMapper auditMapper,
      PartnerRequestMapper partnerMapper,
      FlowRegistry flows,
      OrchestratorProperties properties,
      InquiryProperties inquiry,
      Clock clock) {
    this.transactions = transactions;
    this.timers = timers;
    this.outbox = outbox;
    this.statusMapper = statusMapper;
    this.commandMapper = commandMapper;
    this.auditMapper = auditMapper;
    this.partnerMapper = partnerMapper;
    this.flows = flows;
    this.properties = properties;
    this.inquiry = inquiry;
    this.clock = clock;
  }

  /** Publishes the first state (RECEIVED) of a transaction just inserted, and audits it. */
  public void created(TransactionRecord rec) {
    publishStatus(statusMapper.toStatus(rec), rec.replyTo());
    audit(rec, null, rec.updatedAt());
  }

  /**
   * Applies {@code event} to {@code rec}.
   *
   * @param error set on a move to REJECTED or FAILED
   * @return the updated record
   * @throws mfs.mw.orchestrator.service.saga.InvalidTransitionException if the state does not
   *     accept the event; nothing has been written then
   */
  public TransactionRecord fire(TransactionRecord rec, SagaEvent event, ErrorInfo error) {
    Transition t = SagaMachine.apply(rec.status(), event);
    Instant now = clock.instant();
    TransactionRecord next = t.changesState() ? rec.moved(t.to(), now, error) : rec;

    if (t.changesState()) {
      transactions.update(next);
      transactions.recordStep(rec.txnId(), t.from(), t.to(), event.getClass().getSimpleName(), now);
      publishStatus(statusMapper.toStatus(next), next.replyTo());
      audit(next, t.from(), now);
      log.atInfo()
          .addKeyValue("event", "txn.state.changed")
          .addKeyValue("txnId", rec.txnId().toString())
          .addKeyValue("correlationId", rec.request().correlationId())
          .addKeyValue("from", t.from().name())
          .addKeyValue("to", t.to().name())
          .log("transaction state changed");
    }
    rearm(next, t, now);
    perform(t.effect(), next, now);
    return next;
  }

  /** Publishes a status on the status topic with the record's reply-to header. */
  void publishStatus(StatusMessage msg, String replyTo) {
    outbox.append(
        STATUS_TOPIC,
        msg.txnId(),
        new EventMetadata(msg.correlationId(), msg.txnId(), msg.idempotencyKey(), 1),
        msg,
        replyTo == null ? Map.of() : Map.of(MwHeaders.REPLY_TO, replyTo));
  }

  private void audit(TransactionRecord rec, Status from, Instant at) {
    AuditEvent e = auditMapper.stateChanged(rec, from, at);
    outbox.append(
        AUDIT_TOPIC, e.txnId(), new EventMetadata(e.correlationId(), e.txnId(), e.auditId(), 1), e);
  }

  private void rearm(TransactionRecord rec, Transition t, Instant now) {
    if (t.changesState()) {
      timers.clear(rec.txnId());
    }
    properties
        .deadline(t.to())
        .ifPresent(d -> timers.arm(rec.txnId(), t.to().name(), now.plus(d), !t.changesState()));
  }

  /**
   * Performs the step behind {@code effect} for the transaction's flow (spec 7.1): every flow is
   * built from the same steps, and a step the flow does not have, or one whose ticket is not
   * built yet, is logged as {@code saga.effect.unwired} and skipped. Entering UNKNOWN arms the
   * first status-inquiry slot (spec 7.4); later slots are armed by {@link InquiryService} as each
   * inquiry goes out.
   */
  private void perform(Effect effect, TransactionRecord rec, Instant now) {
    if (effect == Effect.NONE) {
      return;
    }
    if (effect == Effect.SCHEDULE_INQUIRY) {
      scheduleFirstInquiry(rec, now);
      return;
    }
    Optional<Step> step = Step.of(effect);
    Optional<Flow> flow = flows.find(rec.request().txnType());
    if (step.isEmpty() || flow.isEmpty() || !allowed(flow.get(), step.get())) {
      unwired(effect, rec);
      return;
    }
    Duration ttl = properties.commandTtl();
    switch (step.get()) {
      case VALIDATE -> send(commandMapper.validate(rec, now, ttl));
      case POST -> send(commandMapper.post(rec, now, ttl));
      case HOLD -> send(commandMapper.hold(rec, now, ttl));
      case COMMIT -> send(commandMapper.commit(rec, now, ttl));
      case RELEASE -> send(commandMapper.release(rec, now, ttl));
      case QUERY -> send(commandMapper.query(rec, now, ttl));
      case CALL_PARTNER -> callPartner(rec, flow.get().partnerCall().orElseThrow(), now);
      case INQUIRE -> inquire(rec, flow.get(), now);
      case REVERSE -> send(commandMapper.reverse(rec, now, ttl));
    }
  }

  /**
   * The flow's own steps; QUERY belongs to every flow that holds (HOLDING timeout), REVERSE to
   * every flow (any COMPLETED transaction can be compensated once, spec 7.2), INQUIRE to every
   * flow (any flow can reach UNKNOWN, spec 7.4).
   */
  private static boolean allowed(Flow flow, Step step) {
    return flow.steps().contains(step)
        || step == Step.REVERSE
        || step == Step.INQUIRE
        || (step == Step.QUERY && flow.steps().contains(Step.HOLD));
  }

  /** The step name of the status-inquiry timer in {@code saga_timer}. */
  public static final String INQUIRY_TIMER = Status.UNKNOWN.name();

  private void scheduleFirstInquiry(TransactionRecord rec, Instant now) {
    timers.armAttempt(rec.txnId(), INQUIRY_TIMER, inquiry.scheduleFor(partnerId(rec)).first(now), 0);
  }

  /** The partner of the transaction, null for flows without one. */
  static String partnerId(TransactionRecord rec) {
    return rec.request().partner() == null ? null : rec.request().partner().partnerId();
  }

  /**
   * Asks whoever knows the outcome (spec 7.4): with a partner leg the partner (STATUS_QUERY with
   * the PAY's reference), else the core (QueryTransaction, fresh commandId per inquiry).
   */
  private void inquire(TransactionRecord rec, Flow flow, Instant now) {
    Optional<PartnerCall> call = flow.partnerCall();
    if (call.isPresent()) {
      PartnerRequest request = partnerMapper.statusQuery(rec, call.get(), now, properties.partnerTimeout());
      outbox.append(
          call.get().topic(),
          request.txnId(),
          new EventMetadata(request.correlationId(), request.txnId(), request.requestId(), 1),
          request);
    } else {
      send(commandMapper.query(rec, now, properties.commandTtl()));
    }
    log.atInfo()
        .addKeyValue("event", "saga.inquiry.sent")
        .addKeyValue("txnId", rec.txnId().toString())
        .addKeyValue("partnerId", partnerId(rec))
        .addKeyValue("target", call.isPresent() ? "partner" : "core")
        .log("status inquiry sent for an unknown outcome");
  }

  private void unwired(Effect effect, TransactionRecord rec) {
    log.atWarn()
        .addKeyValue("event", "saga.effect.unwired")
        .addKeyValue("txnId", rec.txnId().toString())
        .addKeyValue("effect", effect.name())
        .log("effect belongs to a step that is not wired yet; nothing sent");
  }

  /**
   * A hold the core placed after the transaction stopped waiting for it (a late FundsHeld after a
   * HOLDING timeout ended REJECTED): release it so the payer's funds are not left reserved. The
   * transaction's state does not change; the core's HoldReleased is then ignored.
   */
  public void releaseOrphanHold(TransactionRecord rec, String holdId) {
    send(commandMapper.release(rec.withHold(holdId), clock.instant(), properties.commandTtl()));
    log.atWarn()
        .addKeyValue("event", "saga.hold.orphan_released")
        .addKeyValue("txnId", rec.txnId().toString())
        .addKeyValue("status", rec.status().name())
        .log("hold placed after the transaction ended; releasing it");
  }

  private void callPartner(TransactionRecord rec, PartnerCall call, Instant now) {
    PartnerRequest request = partnerMapper.pay(rec, call, now, properties.partnerTimeout());
    outbox.append(
        call.topic(),
        request.txnId(),
        new EventMetadata(request.correlationId(), request.txnId(), request.requestId(), 1),
        request);
  }

  private void send(CoreCommand command) {
    outbox.append(
        properties.walletCommandTopic(),
        command.txnId(),
        new EventMetadata(command.correlationId(), command.txnId(), command.commandId(), 1),
        command);
  }
}
