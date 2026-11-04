package mfs.mw.orchestrator.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import mfs.mw.orchestrator.config.OrchestratorProperties;
import mfs.mw.orchestrator.dto.AuditEvent;
import mfs.mw.orchestrator.dto.CoreCommand;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.dto.StatusMessage;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.mapper.AuditMapper;
import mfs.mw.orchestrator.mapper.CoreCommandMapper;
import mfs.mw.orchestrator.mapper.StatusMapper;
import mfs.mw.orchestrator.messaging.EventMetadata;
import mfs.mw.orchestrator.messaging.MwHeaders;
import mfs.mw.orchestrator.messaging.OutboxWriter;
import mfs.mw.orchestrator.repository.SagaTimerRepository;
import mfs.mw.orchestrator.repository.TransactionRepository;
import mfs.mw.orchestrator.service.saga.Effect;
import mfs.mw.orchestrator.service.saga.InvalidTransitionException;
import mfs.mw.orchestrator.service.saga.SagaEvent;
import mfs.mw.orchestrator.service.saga.SagaMachine;
import mfs.mw.orchestrator.service.saga.Status;
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
  private final OrchestratorProperties properties;
  private final Clock clock;

  public SagaEngine(
      TransactionRepository transactions,
      SagaTimerRepository timers,
      OutboxWriter outbox,
      StatusMapper statusMapper,
      CoreCommandMapper commandMapper,
      AuditMapper auditMapper,
      OrchestratorProperties properties,
      Clock clock) {
    this.transactions = transactions;
    this.timers = timers;
    this.outbox = outbox;
    this.statusMapper = statusMapper;
    this.commandMapper = commandMapper;
    this.auditMapper = auditMapper;
    this.properties = properties;
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

  private void perform(Effect effect, TransactionRecord rec, Instant now) {
    switch (effect) {
      case NONE -> {}
      case SEND_VALIDATE -> send(commandMapper.validate(rec, now, properties.commandTtl()), rec);
      case SEND_POST -> send(commandMapper.post(rec, now, properties.commandTtl()), rec);
      default ->
          log.atWarn()
              .addKeyValue("event", "saga.effect.unwired")
              .addKeyValue("txnId", rec.txnId().toString())
              .addKeyValue("effect", effect.name())
              .log("effect belongs to a flow that is not wired yet; nothing sent");
    }
  }

  private void send(CoreCommand command, TransactionRecord rec) {
    outbox.append(
        properties.walletCommandTopic(),
        command.txnId(),
        new EventMetadata(command.correlationId(), command.txnId(), command.commandId(), 1),
        command);
  }
}
