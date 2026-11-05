package mfs.mw.admin.api.service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.admin.api.client.OrchestratorQueryClient;
import mfs.mw.admin.api.config.ChangeRequestProperties;
import mfs.mw.admin.api.dto.AdminTxn;
import mfs.mw.admin.api.dto.AuditEvent;
import mfs.mw.admin.api.dto.ChangeRequestBody.TxnActionType;
import mfs.mw.admin.api.dto.ChangeRequestView;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.dto.ReversalRequest;
import mfs.mw.admin.api.dto.TxnAction;
import mfs.mw.admin.api.entity.ChangeRequest;
import mfs.mw.admin.api.exception.ErrorCodes;
import mfs.mw.admin.api.exception.MwException;
import mfs.mw.admin.api.exception.QueryUnavailableException;
import mfs.mw.admin.api.mapper.ChangeRequestMapper;
import mfs.mw.admin.api.messaging.EventMetadata;
import mfs.mw.admin.api.messaging.OutboxWriter;
import mfs.mw.admin.api.repository.ChangeRequestRepository;
import mfs.mw.admin.api.security.AdminRole;
import mfs.mw.admin.api.security.OperatorResolver;
import mfs.mw.admin.api.util.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The maker-checker engine (spec 15.3, ticket 37) for operator actions on transactions (spec
 * 15.2): manual TSQ, force commit / release from MANUAL_REVIEW, reversal of a COMPLETED one.
 *
 * <ul>
 *   <li>A maker ({@link AdminRole#TXN_MAKER}) creates a PENDING request; the action must fit the
 *       transaction's current status, read from mw-orchestrator; one open request per transaction.
 *   <li>A checker ({@link AdminRole#TXN_CHECKER}) who is not the maker (token subject and username
 *       differ) approves or rejects it; the transaction is read again, so a request whose
 *       transaction moved on is refused and stays PENDING.
 *   <li>A request is decided only before {@code expiresAt} (created + 72 h); after that it is
 *       EXPIRED, by the approval attempt or by {@link #expireDue()}.
 * </ul>
 *
 * Each decision runs in one database transaction: the status change, its audit event and (on
 * approval) the message to mw-orchestrator ({@code mw.txn.action.v1}, or a REVERSAL on {@code
 * mw.txn.request.v1}) are committed together through the outbox (spec 6.4, 15.3).
 */
@Service
public class ChangeRequestService {

  private static final Logger log = LoggerFactory.getLogger(ChangeRequestService.class);

  static final String AUDIT_TOPIC = "mw.audit.event.v1";
  static final String ACTION_TOPIC = "mw.txn.action.v1";
  static final String REQUEST_TOPIC = "mw.txn.request.v1";

  private final ChangeRequestRepository requests;
  private final ContentChangeService content;
  private final OrchestratorQueryClient orchestrator;
  private final OutboxWriter outbox;
  private final TransactionTemplate tx;
  private final ChangeRequestProperties props;
  private final Clock clock;

  public ChangeRequestService(
      ChangeRequestRepository requests,
      ContentChangeService content,
      OrchestratorQueryClient orchestrator,
      OutboxWriter outbox,
      TransactionTemplate tx,
      ChangeRequestProperties props,
      Clock clock) {
    this.requests = requests;
    this.content = content;
    this.orchestrator = orchestrator;
    this.outbox = outbox;
    this.tx = tx;
    this.props = props;
    this.clock = clock;
  }

  // --- maker ----------------------------------------------------------------------------------

  public ChangeRequestView create(Operator maker, UUID txnId, TxnActionType action, String reason) {
    if (!maker.hasAny(AdminRole.TXN_MAKER)) {
      throw OperatorResolver.forbidden("Asking for a transaction action needs the ops-maker role");
    }
    AdminTxn txn = transaction(txnId);
    String target = target(action, txn).orElseThrow(() -> notAllowed(action, txn.status()));
    Instant now = clock.instant();
    Map<String, Object> payload = new LinkedHashMap<>();
    if (action == TxnActionType.REVERSE) {
      payload.put("amount", Map.of("valueMinor", txn.amount().valueMinor(), "currency", txn.amount().currency()));
    }
    ChangeRequest cr =
        new ChangeRequest(
            UUID.randomUUID(), ChangeRequest.TRANSACTION, txnId.toString(), action.name(), payload,
            ChangeRequestMapper.diff(txn.status(), target), maker.username(), maker.subject(),
            ChangeRequest.Status.PENDING, null, null, reason, null, now, null, now.plus(props.ttl()));
    tx.executeWithoutResult(
        s -> {
          if (!requests.insert(cr)) {
            throw new MwException(ErrorCodes.VAL_REQUEST_OPEN, "This transaction already has an open change request")
                .withStatus(HttpStatus.CONFLICT);
          }
          audit(cr, ChangeRequestMapper.created(cr, correlationId()));
        });
    logDecision("admin.change_request.created", cr, maker.username());
    return ChangeRequestMapper.view(cr);
  }

  // --- checker --------------------------------------------------------------------------------

  public ChangeRequestView approve(Operator checker, UUID id) {
    ChangeRequest cr = decidable(checker, id);
    if (ContentChangeService.handles(cr.resourceType())) {
      return approveContent(checker, cr);
    }
    AdminTxn txn = transaction(UUID.fromString(cr.resourceId()));
    TxnActionType action = TxnActionType.valueOf(cr.action());
    if (target(action, txn).isEmpty()) {
      throw notAllowed(action, txn.status());
    }
    Instant now = clock.instant();
    String correlationId = correlationId();
    Map<String, Object> payload = new LinkedHashMap<>(cr.payload());
    Runnable send;
    String sentAs;
    if (action == TxnActionType.REVERSE) {
      UUID reversalTxnId = UuidV7.at(now);
      ReversalRequest rev = ChangeRequestMapper.reversal(cr, reversalTxnId, txn.amount(), checker.username(), correlationId, now);
      payload.put("reversalTxnId", reversalTxnId.toString());
      sentAs = REQUEST_TOPIC;
      send = () -> outbox.append(
          REQUEST_TOPIC, rev.txnId(), new EventMetadata(correlationId, rev.txnId(), rev.idempotencyKey(), 1), rev);
    } else {
      UUID actionId = UUID.randomUUID();
      TxnAction msg = ChangeRequestMapper.action(cr, actionId, checker.username(), correlationId, now);
      payload.put("actionId", actionId.toString());
      sentAs = ACTION_TOPIC;
      send = () -> outbox.append(
          ACTION_TOPIC, msg.txnId(), new EventMetadata(correlationId, msg.txnId(), msg.actionId(), 1), msg);
    }
    tx.executeWithoutResult(
        s -> {
          if (!requests.decide(id, ChangeRequest.Status.APPROVED, checker.username(), checker.subject(), null, payload, now)) {
            throw decided();
          }
          send.run();
          audit(cr, ChangeRequestMapper.approved(cr, checker.username(), sentAs, correlationId, now));
        });
    logDecision("admin.change_request.approved", cr, checker.username());
    return found(id);
  }

  /** A USSD menu or template version (ticket 39): approved and published in one transaction. */
  private ChangeRequestView approveContent(Operator checker, ChangeRequest cr) {
    Instant now = clock.instant();
    String correlationId = correlationId();
    tx.executeWithoutResult(
        s -> {
          if (!requests.decide(cr.id(), ChangeRequest.Status.APPROVED, checker.username(), checker.subject(), null, cr.payload(), now)) {
            throw decided();
          }
          String sentAs = content.publish(cr, checker.username(), correlationId, now);
          audit(cr, ChangeRequestMapper.approved(cr, checker.username(), sentAs, correlationId, now));
        });
    logDecision("admin.change_request.approved", cr, checker.username());
    return found(cr.id());
  }

  public ChangeRequestView reject(Operator checker, UUID id, String reason) {
    ChangeRequest cr = decidable(checker, id);
    Instant now = clock.instant();
    tx.executeWithoutResult(
        s -> {
          if (!requests.decide(id, ChangeRequest.Status.REJECTED, checker.username(), checker.subject(), reason, cr.payload(), now)) {
            throw decided();
          }
          if (ContentChangeService.handles(cr.resourceType())) {
            content.decided(cr, ChangeRequest.Status.REJECTED, checker.username(), now);
          }
          audit(cr, ChangeRequestMapper.rejected(cr, checker.username(), reason, correlationId(), now));
        });
    logDecision("admin.change_request.rejected", cr, checker.username());
    return found(id);
  }

  // --- expiry and reads -----------------------------------------------------------------------

  /** Marks every PENDING request past its {@code expiresAt} EXPIRED, with its audit; returns how many. */
  public int expireDue() {
    Instant now = clock.instant();
    Integer n =
        tx.execute(
            s -> {
              List<ChangeRequest> due = requests.duePendingForUpdate(now, props.expiryBatch());
              due.forEach(cr -> expireOne(cr, now));
              return due.size();
            });
    return n == null ? 0 : n;
  }

  public ChangeRequestView get(Operator operator, UUID id) {
    requireReader(operator);
    return found(id);
  }

  public ChangeRequestView.Page list(Operator operator, ChangeRequest.Status status, String resourceId, int limit, int offset) {
    requireReader(operator);
    return new ChangeRequestView.Page(
        requests.list(status, resourceId, limit, offset).stream().map(ChangeRequestMapper::view).toList(), limit, offset);
  }

  // --- rules ----------------------------------------------------------------------------------

  /**
   * The status an action leads to from the transaction's current one, empty when it does not fit
   * (spec 7.2, 7.4): INQUIRE in UNKNOWN / MANUAL_REVIEW (stays), FORCE_COMMIT / FORCE_RELEASE in
   * MANUAL_REVIEW, REVERSE of a COMPLETED, not yet reversed, non-reversal transaction.
   */
  static Optional<String> target(TxnActionType action, AdminTxn txn) {
    String s = txn.status();
    return Optional.ofNullable(
        switch (action) {
          case INQUIRE -> "UNKNOWN".equals(s) || "MANUAL_REVIEW".equals(s) ? s : null;
          case FORCE_COMMIT -> "MANUAL_REVIEW".equals(s) ? "COMMITTING" : null;
          case FORCE_RELEASE -> "MANUAL_REVIEW".equals(s) ? "RELEASING" : null;
          case REVERSE ->
              "COMPLETED".equals(s) && txn.reversalTxnId() == null && !"REVERSAL".equals(txn.txnType())
                  ? "REVERSING"
                  : null;
        });
  }

  /** A request this checker may decide now; an expired one is marked EXPIRED first, then refused. */
  private ChangeRequest decidable(Operator checker, UUID id) {
    ChangeRequest cr = requests.find(id).orElseThrow(ChangeRequestService::notFound);
    boolean contentRequest = ContentChangeService.handles(cr.resourceType());
    if (!checker.hasAny(contentRequest ? AdminRole.CONTENT_CHECKER : AdminRole.TXN_CHECKER)) {
      throw OperatorResolver.forbidden("Deciding this change request needs the ops-checker role");
    }
    if (cr.makerSub().equals(checker.subject()) || cr.makerId().equals(checker.username())) {
      log.atWarn()
          .addKeyValue("event", "admin.change_request.own_decision_refused")
          .addKeyValue("errorCode", ErrorCodes.AUTH_OWN_REQUEST)
          .log("maker tried to decide own change request");
      throw new MwException(ErrorCodes.AUTH_OWN_REQUEST, "The maker of a change request cannot decide it")
          .withStatus(HttpStatus.FORBIDDEN);
    }
    if (cr.status() == ChangeRequest.Status.EXPIRED) {
      throw expired();
    }
    if (cr.status() != ChangeRequest.Status.PENDING) {
      throw decided();
    }
    Instant now = clock.instant();
    if (cr.expiredAt(now)) {
      tx.executeWithoutResult(
          s -> requests.find(id).filter(c -> c.status() == ChangeRequest.Status.PENDING).ifPresent(c -> expireOne(c, now)));
      throw expired();
    }
    return cr;
  }

  private void expireOne(ChangeRequest cr, Instant now) {
    if (requests.decide(cr.id(), ChangeRequest.Status.EXPIRED, null, null, null, cr.payload(), now)) {
      if (ContentChangeService.handles(cr.resourceType())) {
        content.decided(cr, ChangeRequest.Status.EXPIRED, null, now);
      }
      audit(cr, ChangeRequestMapper.expired(cr, correlationId(), now));
      logDecision("admin.change_request.expired", cr, null);
    }
  }

  private AdminTxn transaction(UUID txnId) {
    try {
      return orchestrator
          .detail(txnId, false)
          .map(AdminTxn.Detail::transaction)
          .orElseThrow(() -> new MwException(ErrorCodes.VAL_NOT_FOUND, "No such transaction").withStatus(HttpStatus.NOT_FOUND));
    } catch (QueryUnavailableException e) {
      log.atWarn()
          .addKeyValue("event", "admin.txn_query.unavailable")
          .addKeyValue("errorCode", ErrorCodes.SYS_QUERY_UNAVAILABLE)
          .log("transaction query unavailable: {}", e.getMessage());
      throw new MwException(ErrorCodes.SYS_QUERY_UNAVAILABLE, "Transaction query temporarily unavailable", e)
          .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
          .withRetryable(true);
    }
  }

  /** Key and mw-txn-id the txnId; a content request has none: key its resource, mw-txn-id its id. */
  private void audit(ChangeRequest cr, AuditEvent e) {
    String key = e.txnId() == null ? e.resource() : e.txnId();
    String txnHeader = e.txnId() == null ? cr.id().toString() : e.txnId();
    outbox.append(AUDIT_TOPIC, key, new EventMetadata(e.correlationId(), txnHeader, e.auditId(), 1), e);
  }

  private ChangeRequestView found(UUID id) {
    return requests.find(id).map(ChangeRequestMapper::view).orElseThrow(ChangeRequestService::notFound);
  }

  private static void requireReader(Operator operator) {
    if (!operator.hasAny(AdminRole.READ_TRANSACTIONS)) {
      throw OperatorResolver.forbidden("This operation needs a transaction viewing role");
    }
  }

  private static String correlationId() {
    String c = MDC.get("correlationId");
    return c == null || c.isBlank() ? "admin-" + UUID.randomUUID() : c;
  }

  private static void logDecision(String event, ChangeRequest cr, String checker) {
    log.atInfo()
        .addKeyValue("event", event)
        .addKeyValue(ChangeRequest.TRANSACTION.equals(cr.resourceType()) ? "txnId" : "resourceId", cr.resourceId())
        .addKeyValue("changeRequestId", cr.id().toString())
        .addKeyValue("action", cr.action())
        .addKeyValue("makerId", cr.makerId())
        .addKeyValue("checkerId", checker)
        .log("change request " + event.substring(event.lastIndexOf('.') + 1));
  }

  private static MwException notAllowed(TxnActionType action, String status) {
    return new MwException(
            ErrorCodes.VAL_ACTION_NOT_ALLOWED, "Action " + action + " does not fit a transaction in status " + status)
        .withStatus(HttpStatus.CONFLICT);
  }

  private static MwException notFound() {
    return new MwException(ErrorCodes.VAL_NOT_FOUND, "No such change request").withStatus(HttpStatus.NOT_FOUND);
  }

  private static MwException expired() {
    return new MwException(ErrorCodes.VAL_REQUEST_EXPIRED, "The change request expired before a decision")
        .withStatus(HttpStatus.CONFLICT);
  }

  private static MwException decided() {
    return new MwException(ErrorCodes.VAL_REQUEST_DECIDED, "The change request was already decided")
        .withStatus(HttpStatus.CONFLICT);
  }
}
