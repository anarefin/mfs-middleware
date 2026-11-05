package mfs.mw.orchestrator.service;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.orchestrator.dto.AuditEvent;
import mfs.mw.orchestrator.dto.TxnAction;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.exception.ErrorCodes;
import mfs.mw.orchestrator.exception.InvalidTransitionException;
import mfs.mw.orchestrator.mapper.AuditMapper;
import mfs.mw.orchestrator.messaging.EventMetadata;
import mfs.mw.orchestrator.messaging.Inbox;
import mfs.mw.orchestrator.messaging.OutboxWriter;
import mfs.mw.orchestrator.repository.TransactionRepository;
import mfs.mw.orchestrator.service.saga.SagaEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies operator actions from {@code mw.txn.action.v1} (ticket 37, spec 7.2, 15.2), each approved
 * by maker-checker in mw-admin-api: FORCE_COMMIT / FORCE_RELEASE are the ManualCommit /
 * ManualRelease exits of MANUAL_REVIEW, INQUIRE one status inquiry now (UNKNOWN or MANUAL_REVIEW).
 * Once per {@code actionId} (inbox). An action the state or the flow does not allow (the state
 * moved on since the maker asked, a P2P has no commit step) changes nothing. Every action, applied
 * or refused, is audited ({@code txn.operator.action}, actor the checker) in the same transaction.
 */
@Service
public class OperatorActionService {

  private static final Logger log = LoggerFactory.getLogger(OperatorActionService.class);

  static final String CONSUMER = "orchestrator.txn-actions";

  private final Inbox inbox;
  private final TransactionRepository transactions;
  private final SagaEngine engine;
  private final AuditMapper audits;
  private final OutboxWriter outbox;
  private final Clock clock;

  public OperatorActionService(
      Inbox inbox, TransactionRepository transactions, SagaEngine engine, AuditMapper audits, OutboxWriter outbox,
      Clock clock) {
    this.inbox = inbox;
    this.transactions = transactions;
    this.engine = engine;
    this.audits = audits;
    this.outbox = outbox;
    this.clock = clock;
  }

  @Transactional
  public void handle(TxnAction action) {
    inbox.processOnce(CONSUMER, action.actionId(), () -> apply(action));
  }

  private void apply(TxnAction action) {
    Optional<TransactionRecord> found = parse(action.txnId()).flatMap(transactions::findForUpdate);
    if (found.isEmpty()) {
      log.atWarn()
          .addKeyValue("event", "txn.action.unknown_txn")
          .addKeyValue("txnId", action.txnId())
          .addKeyValue("action", action.action())
          .log("operator action for an unknown transaction; dropped");
      return;
    }
    TransactionRecord rec = found.get();
    Optional<SagaEvent> event = event(action.action());
    TransactionRecord after = rec;
    String refused = null;
    if (event.isEmpty()) {
      refused = ErrorCodes.VAL_ACTION_NOT_ALLOWED;
    } else {
      try {
        after = engine.fire(rec, event.get(), null);
      } catch (InvalidTransitionException e) {
        refused = ErrorCodes.VAL_ACTION_NOT_ALLOWED;
      }
    }
    AuditEvent audit = audits.operatorAction(rec, action, rec.status(), after.status(), refused, clock.instant());
    outbox.append(
        SagaEngine.AUDIT_TOPIC, audit.txnId(), new EventMetadata(audit.correlationId(), audit.txnId(), audit.auditId(), 1),
        audit);
    var line = refused == null ? log.atInfo() : log.atWarn();
    line.addKeyValue("event", refused == null ? "txn.action.applied" : "txn.action.refused")
        .addKeyValue("txnId", action.txnId())
        .addKeyValue("correlationId", audit.correlationId())
        .addKeyValue("action", action.action())
        .addKeyValue("status", rec.status().name())
        .addKeyValue("errorCode", refused)
        .log(refused == null ? "operator action applied" : "operator action not allowed in this state; nothing changed");
  }

  private static Optional<SagaEvent> event(String action) {
    return Optional.ofNullable(
        switch (action == null ? "" : action) {
          case TxnAction.FORCE_COMMIT -> new SagaEvent.ManualCommit();
          case TxnAction.FORCE_RELEASE -> new SagaEvent.ManualRelease();
          case TxnAction.INQUIRE -> new SagaEvent.ManualInquiry();
          default -> null;
        });
  }

  private static Optional<UUID> parse(String txnId) {
    try {
      return txnId == null ? Optional.empty() : Optional.of(UUID.fromString(txnId));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
