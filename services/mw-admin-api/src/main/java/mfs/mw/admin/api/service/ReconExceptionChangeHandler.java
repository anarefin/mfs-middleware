package mfs.mw.admin.api.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import mfs.mw.admin.api.client.OrchestratorQueryClient;
import mfs.mw.admin.api.client.ReconExceptionClient;
import mfs.mw.admin.api.dto.AdminRole;
import mfs.mw.admin.api.dto.AdminTxn;
import mfs.mw.admin.api.dto.ChangeRequestBody.TxnActionType;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.dto.ReconExceptionView;
import mfs.mw.admin.api.dto.ReversalRequest;
import mfs.mw.admin.api.dto.TxnAction;
import mfs.mw.admin.api.entity.ChangeRequest;
import mfs.mw.admin.api.exception.ErrorCodes;
import mfs.mw.admin.api.exception.MwException;
import mfs.mw.admin.api.exception.QueryUnavailableException;
import mfs.mw.admin.api.mapper.ChangeRequestMapper;
import mfs.mw.admin.api.messaging.EventMetadata;
import mfs.mw.admin.api.messaging.OutboxWriter;
import mfs.mw.admin.api.repository.ReconActionPendingRepository;
import mfs.mw.admin.api.util.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Approval of an action on a reconciliation exception (ticket 127b, UC-22; resourceType {@code recon_exception},
 * resourceId the mw-recon exception id). A {@code recon-checker} approves. The exception is read again from mw-recon
 * (still OPEN or EXPLAINED, same transaction) and the transaction from mw-orchestrator (the action still fits its
 * status, the ticket 37 rules); else 409 and the request stays PENDING. The approval then takes the ticket 37 path in
 * the decision transaction: FORCE_COMMIT on {@code mw.txn.action.v1} or a REVERSAL on {@code mw.txn.request.v1}
 * through the outbox, with a {@code recon_action_pending} row. Only after that commit mw-recon is told ({@code POST
 * .../actions}, idempotent on the change request id; re-sent by the retry job until mw-recon answers), so a reject
 * or expiry that wins never leaves an ACTIONED exception; mw-recon resolves it when the
 * transaction's outcome arrives. mw-recon moves no money.
 */
@Component
public class ReconExceptionChangeHandler implements ResourceChangeHandler {

  public static final String RESOURCE = "recon_exception";

  private static final Logger log = LoggerFactory.getLogger(ReconExceptionChangeHandler.class);

  /** A pending action is sent again once its last attempt is this old. */
  static final Duration RETRY_AFTER = Duration.ofSeconds(30);
  static final int RETRY_BATCH = 50;

  private final ReconExceptionClient recon;
  private final OrchestratorQueryClient orchestrator;
  private final OutboxWriter outbox;
  private final ReconActionPendingRepository pending;
  private final Clock clock;

  public ReconExceptionChangeHandler(ReconExceptionClient recon, OrchestratorQueryClient orchestrator,
      OutboxWriter outbox, ReconActionPendingRepository pending, Clock clock) {
    this.recon = recon;
    this.orchestrator = orchestrator;
    this.outbox = outbox;
    this.pending = pending;
    this.clock = clock;
  }

  @Override
  public String resourceType() {
    return RESOURCE;
  }

  @Override
  public Set<AdminRole> checkers() {
    return AdminRole.RECON_EXCEPTION_CHECKER;
  }

  /** What the maker and the checker both check: the open exception, its transaction and the status it leads to. */
  public record Checked(ReconExceptionView exception, AdminTxn txn, String target) {}

  /** The exception still open with a transaction, and the action fitting that transaction's status now. */
  Checked check(UUID exceptionId, TxnActionType action) {
    ReconExceptionView exception = exception(exceptionId);
    if (!exception.actionable()) {
      throw new MwException(ErrorCodes.VAL_ACTION_NOT_ALLOWED,
          "The exception is " + exception.state() + "; an action needs it OPEN or EXPLAINED")
          .withStatus(HttpStatus.CONFLICT);
    }
    if (exception.txnId() == null || exception.txnId().isBlank()) {
      throw new MwException(ErrorCodes.VAL_ACTION_NOT_ALLOWED, "The exception has no transaction to act on")
          .withStatus(HttpStatus.CONFLICT);
    }
    AdminTxn txn = transaction(UUID.fromString(exception.txnId()));
    String target = ChangeRequestService.target(action, txn).orElseThrow(() ->
        new MwException(ErrorCodes.VAL_ACTION_NOT_ALLOWED,
            "Action " + action + " does not fit a transaction in status " + txn.status())
            .withStatus(HttpStatus.CONFLICT));
    return new Checked(exception, txn, target);
  }

  @Override
  public Approval prepare(ChangeRequest cr, Operator checker, Instant now, String correlationId) {
    TxnActionType action = TxnActionType.valueOf(cr.action());
    Checked checked = check(UUID.fromString(cr.resourceId()), action);
    String txnId = checked.txn().txnId();
    if (!txnId.equals(String.valueOf(cr.payload().get("txnId")))) {
      throw new MwException(ErrorCodes.VAL_ACTION_NOT_ALLOWED, "The exception now names another transaction")
          .withStatus(HttpStatus.CONFLICT);
    }
    // the ticket 37 messages carry the transaction as their resource
    ChangeRequest asTxn = new ChangeRequest(cr.id(), ChangeRequest.TRANSACTION, txnId, cr.action(), cr.payload(),
        cr.diff(), cr.makerId(), cr.makerSub(), cr.status(), cr.checkerId(), cr.checkerSub(), cr.reason(),
        cr.decisionReason(), cr.createdAt(), cr.decidedAt(), cr.expiresAt());
    Map<String, Object> payload = new LinkedHashMap<>(cr.payload());
    if (action == TxnActionType.REVERSE) {
      UUID reversalTxnId = UuidV7.at(now);
      ReversalRequest rev =
          ChangeRequestMapper.reversal(asTxn, reversalTxnId, checked.txn().amount(), checker.username(), correlationId, now);
      payload.put("reversalTxnId", reversalTxnId.toString());
      return new Approval(payload, ChangeRequestService.REQUEST_TOPIC, () -> {
        outbox.append(ChangeRequestService.REQUEST_TOPIC, rev.txnId(),
            new EventMetadata(correlationId, rev.txnId(), rev.idempotencyKey(), 1), rev);
        keepForRecon(cr, checker, now);
        return Map.of();
      });
    }
    UUID actionId = UUID.randomUUID();
    TxnAction msg = ChangeRequestMapper.action(asTxn, actionId, checker.username(), correlationId, now);
    payload.put("actionId", actionId.toString());
    return new Approval(payload, ChangeRequestService.ACTION_TOPIC, () -> {
      outbox.append(ChangeRequestService.ACTION_TOPIC, msg.txnId(),
          new EventMetadata(correlationId, msg.txnId(), msg.actionId(), 1), msg);
      keepForRecon(cr, checker, now);
      return Map.of();
    });
  }

  /**
   * REC-05 (M9 review): in the decision transaction, the approved action is kept until mw-recon has it, so an
   * mw-recon outage after the commit never leaves the exception OPEN with the action already taken.
   */
  private void keepForRecon(ChangeRequest cr, Operator checker, Instant now) {
    pending.insert(cr.id(), UUID.fromString(cr.resourceId()), cr.action(), checker.username(), now);
  }

  /**
   * Tells mw-recon after the commit. Once mw-recon answered (recorded, or refused: already actioned or resolved)
   * the pending row is dropped; no answer keeps it for {@link #retryDue()} (REC-05, M9 review).
   */
  @Override
  public void afterApproved(ChangeRequest cr, Map<String, Object> payload, Operator checker) {
    deliver(new ReconActionPendingRepository.Pending(cr.id(), UUID.fromString(cr.resourceId()), cr.action(),
        checker.username(), 0));
  }

  /** Sends again every approved action mw-recon has not answered whose last attempt is due (the retry job). */
  public void retryDue() {
    for (ReconActionPendingRepository.Pending p : pending.due(clock.instant().minus(RETRY_AFTER), RETRY_BATCH)) {
      deliver(p);
    }
  }

  private void deliver(ReconActionPendingRepository.Pending p) {
    String outcome;
    try {
      outcome = recon.recordAction(p.exceptionId(), p.action(), p.changeRequestId(), p.checkerId()).name();
    } catch (RuntimeException e) {
      outcome = "UNKNOWN";
    }
    if ("UNKNOWN".equals(outcome)) {
      pending.attempted(p.changeRequestId(), clock.instant());
    } else {
      pending.delete(p.changeRequestId());
    }
    var event = "RECORDED".equals(outcome) ? log.atInfo() : log.atWarn();
    event.addKeyValue("event", "admin.recon_exception.recorded")
        .addKeyValue("resourceId", p.exceptionId().toString())
        .addKeyValue("changeRequestId", p.changeRequestId().toString())
        .addKeyValue("outcome", outcome)
        .addKeyValue("attempts", p.attempts() + 1)
        .log("Approved recon exception action told to mw-recon: {}", outcome);
  }

  ReconExceptionView exception(UUID id) {
    try {
      return recon.get(id).orElseThrow(() ->
          new MwException(ErrorCodes.VAL_NOT_FOUND, "No such reconciliation exception").withStatus(HttpStatus.NOT_FOUND));
    } catch (QueryUnavailableException e) {
      throw unavailable("Reconciliation queue temporarily unavailable", e);
    }
  }

  private AdminTxn transaction(UUID txnId) {
    try {
      return orchestrator.detail(txnId, false).map(AdminTxn.Detail::transaction).orElseThrow(() ->
          new MwException(ErrorCodes.VAL_NOT_FOUND, "No such transaction").withStatus(HttpStatus.NOT_FOUND));
    } catch (QueryUnavailableException e) {
      throw unavailable("Transaction query temporarily unavailable", e);
    }
  }

  private static MwException unavailable(String message, QueryUnavailableException e) {
    log.atWarn()
        .addKeyValue("event", "admin.recon_exception.query_unavailable")
        .addKeyValue("errorCode", ErrorCodes.SYS_QUERY_UNAVAILABLE)
        .log("{}: {}", message, e.getMessage());
    return new MwException(ErrorCodes.SYS_QUERY_UNAVAILABLE, message, e)
        .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
        .withRetryable(true);
  }
}
