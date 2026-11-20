package mfs.mw.admin.api.service;

import mfs.mw.admin.api.util.CorrelationIds;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import mfs.mw.admin.api.client.OrchestratorQueryClient;
import mfs.mw.admin.api.config.ChangeRequestProperties;
import mfs.mw.admin.api.dto.AdminTxn;
import mfs.mw.admin.api.dto.AuditEvent;
import mfs.mw.admin.api.dto.ChangeRequestBody.TxnActionType;
import mfs.mw.admin.api.dto.ChangeRequestView;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.dto.ReversalRequest;
import mfs.mw.admin.api.dto.TxnAction;
import mfs.mw.admin.api.dto.UnlockWalletCommand;
import mfs.mw.admin.api.dto.WalletLockCommand;
import mfs.mw.admin.api.dto.WalletChangeRequestBody.WalletActionType;
import mfs.mw.admin.api.entity.ChangeRequest;
import mfs.mw.admin.api.entity.Partner;
import mfs.mw.admin.api.exception.ErrorCodes;
import mfs.mw.admin.api.exception.MwException;
import mfs.mw.admin.api.exception.QueryUnavailableException;
import mfs.mw.admin.api.mapper.ChangeRequestMapper;
import mfs.mw.admin.api.messaging.EventMetadata;
import mfs.mw.admin.api.messaging.OutboxWriter;
import mfs.mw.admin.api.repository.ChangeRequestRepository;
import mfs.mw.admin.api.dto.AdminRole;
import mfs.mw.admin.api.exception.ForbiddenException;
import mfs.mw.admin.api.util.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
  /** UnlockWallet goes straight to the core (ticket 52; wallet topic per the M2 gate split). */
  static final String CORE_COMMAND_TOPIC = "core.wallet.command.v1";
  /** mw-auth's wallet lock commands (ticket 70): the unlock lifts mw-auth's PIN and ADMIN locks. */
  static final String WALLET_LOCK_TOPIC = "auth.wallet-lock.v1";
  static final String PIN_RESET_DONE = "RESET";
  static final String WALLET_LOCKED = "LOCKED";
  static final String WALLET_ACTIVE = "ACTIVE";
  /** A wallet id here is the wallet's MSISDN in the 11-digit local form. */
  static final Pattern WALLET_ID = Pattern.compile("^01[3-9]\\d{8}$");

  private final ChangeRequestRepository requests;
  private final OrchestratorQueryClient orchestrator;
  private final OutboxWriter outbox;
  private final TransactionTemplate tx;
  private final ChangeRequestProperties props;
  private final Clock clock;
  private final Map<String, ResourceChangeHandler> handlers;
  /** The reconciler leaves a claim alone until its last attempt is older than lease + read timeout. */
  private final java.time.Duration attemptGrace;

  public ChangeRequestService(
      ChangeRequestRepository requests,
      OrchestratorQueryClient orchestrator,
      OutboxWriter outbox,
      TransactionTemplate tx,
      ChangeRequestProperties props,
      Clock clock,
      List<ResourceChangeHandler> handlers,
      mfs.mw.admin.api.config.AdminApiProperties api) {
    this.attemptGrace = props.approvingLease().plus(api.readTimeout());
    this.requests = requests;
    this.orchestrator = orchestrator;
    this.outbox = outbox;
    this.tx = tx;
    this.props = props;
    this.clock = clock;
    Map<String, ResourceChangeHandler> byType = new LinkedHashMap<>();
    handlers.forEach(h -> byType.put(h.resourceType(), h));
    this.handlers = Map.copyOf(byType);
  }

  // --- maker ----------------------------------------------------------------------------------

  public ChangeRequestView create(Operator maker, UUID txnId, TxnActionType action, String reason) {
    if (!maker.hasAny(AdminRole.TXN_MAKER)) {
      throw new ForbiddenException("Asking for a transaction action needs the ops-maker role");
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
    insert(cr, "This transaction already has an open change request");
    logDecision("admin.change_request.created", cr, maker.username());
    return ChangeRequestMapper.view(cr);
  }

  /**
   * Opens a PENDING request on another resource type (ticket 38: partners); the caller checked
   * the maker's role and built payload and diff. One open request per resource.
   */
  public ChangeRequestView open(
      Operator maker, String resourceType, String resourceId, String action, Map<String, Object> payload,
      Map<String, Object> diff, String reason) {
    return open(maker, resourceType, resourceId, action, reason, () -> new Opening(payload, diff, cr -> {}));
  }

  /**
   * What a request is opened with, built inside the opening transaction (ticket 39: the content
   * version number), and what is stored with it there once the request is in.
   */
  public record Opening(Map<String, Object> payload, Map<String, Object> diff, Consumer<ChangeRequest> alongside) {}

  /**
   * Opens a PENDING request whose payload and diff are built inside the opening transaction; the
   * request, its creation audit and {@link Opening#alongside()} commit together. One open request
   * per resource ({@code 409 MW-VAL-031}, everything rolls back).
   */
  public ChangeRequestView open(
      Operator maker, String resourceType, String resourceId, String action, String reason, Supplier<Opening> opening) {
    handler(resourceType);
    Instant now = clock.instant();
    ChangeRequest cr =
        tx.execute(
            s -> {
              Opening o = opening.get();
              ChangeRequest c =
                  new ChangeRequest(
                      UUID.randomUUID(), resourceType, resourceId, action, o.payload(), o.diff(), maker.username(),
                      maker.subject(), ChangeRequest.Status.PENDING, null, null, reason, null, now, null,
                      now.plus(props.ttl()));
              insert(c, "This " + resourceType + " already has an open change request");
              o.alongside().accept(c);
              return c;
            });
    logDecision("admin.change_request.created", cr, maker.username());
    return ChangeRequestMapper.view(cr);
  }

  /**
   * Ops unlock of a PIN-locked wallet (ticket 52). The middleware cannot see the wallet's state (the
   * core owns it), so the request is taken for any well-formed wallet id; the core refuses the
   * approved command when the wallet is not locked ({@code UnlockRejected WALLET_NOT_LOCKED}).
   */
  public ChangeRequestView createForWallet(Operator maker, String walletId, WalletActionType action, String reason) {
    if (!maker.hasAny(AdminRole.TXN_MAKER)) {
      throw new ForbiddenException("Asking for a wallet action needs the ops-maker role");
    }
    if (walletId == null || !WALLET_ID.matcher(walletId).matches()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "The wallet id must be an 11-digit MSISDN")
          .withStatus(HttpStatus.BAD_REQUEST);
    }
    Instant now = clock.instant();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put(ChangeRequestMapper.UNLOCK_TXN_ID, UuidV7.at(now).toString());
    boolean reset = action == WalletActionType.PIN_RESET;
    ChangeRequest cr =
        new ChangeRequest(
            UUID.randomUUID(), reset ? ChangeRequest.PIN_RESET : ChangeRequest.WALLET, walletId, action.name(), payload,
            ChangeRequestMapper.diff(WALLET_LOCKED, reset ? PIN_RESET_DONE : WALLET_ACTIVE), maker.username(),
            maker.subject(),
            ChangeRequest.Status.PENDING, null, null, reason, null, now, null, now.plus(props.ttl()));
    insert(cr, "This wallet already has an open change request");
    logDecision("admin.change_request.created", cr, maker.username());
    return ChangeRequestMapper.view(cr);
  }

  // --- checker --------------------------------------------------------------------------------

  public ChangeRequestView approve(Operator checker, UUID id) {
    ChangeRequest cr = decidable(checker, id, true);
    if (ChangeRequest.PIN_RESET.equals(cr.resourceType())) {
      return approvePinReset(checker, cr);
    }
    if (ChangeRequest.WALLET.equals(cr.resourceType())) {
      return approveWallet(checker, cr);
    }
    if (!ChangeRequest.TRANSACTION.equals(cr.resourceType())) {
      return approveOther(checker, cr);
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
          audit(ChangeRequestMapper.approved(cr, checker.username(), sentAs, correlationId, now), cr);
        });
    logDecision("admin.change_request.approved", cr, checker.username());
    return found(id);
  }

  private ChangeRequestView approveOther(Operator checker, ChangeRequest cr) {
    if (handler(cr.resourceType()).claimFirst()) {
      return approveClaimFirst(checker, cr);
    }
    Instant now = clock.instant();
    String correlationId = correlationId();
    ResourceChangeHandler.Approval approval = handler(cr.resourceType()).prepare(cr, checker, now, correlationId);
    tx.executeWithoutResult(
        s -> {
          Map<String, Object> payload = new LinkedHashMap<>(approval.payload());
          payload.putAll(approval.apply().get());
          if (!requests.decide(cr.id(), ChangeRequest.Status.APPROVED, checker.username(), checker.subject(), null, payload, now)) {
            throw decided();
          }
          audit(ChangeRequestMapper.approved(cr, checker.username(), approval.sentAs(), correlationId, now), cr);
        });
    logDecision("admin.change_request.approved", cr, checker.username());
    return found(cr.id());
  }

  /**
   * Claim-first approval (ticket 75, see {@link ResourceChangeHandler#claimFirst()}): (1) PENDING ->
   * APPROVING in one conditional update (a retry of a request already APPROVING skips it); (2) the
   * handler's remote call, idempotent on the change request id; (3) APPROVING -> APPROVED with the
   * payload and the audit in one transaction. A definite refusal of the call (4xx) puts the request
   * back to PENDING; a call without an answer (timeout, 5xx) leaves it APPROVING. A parallel reject
   * or expiry acts on PENDING only, so it can never win after the call: an applied change always
   * belongs to an APPROVED (or still APPROVING) request.
   */
  private ChangeRequestView approveClaimFirst(Operator checker, ChangeRequest cr) {
    ResourceChangeHandler h = handler(cr.resourceType());
    Instant now = clock.instant();
    ChangeRequestRepository.Claim claim;
    if (cr.status() == ChangeRequest.Status.PENDING) {
      h.precheck(cr);
      claim = new ChangeRequestRepository.Claim(checker.username(), checker.subject(), UUID.randomUUID(), now);
      Boolean claimed =
          tx.execute(s -> requests.claim(cr.id(), claim.checkerId(), claim.checkerSub(), claim.token(), claim.claimedAt()));
      if (!Boolean.TRUE.equals(claimed)) {
        throw decided();
      }
      logDecision("admin.change_request.approving", cr, checker.username());
    } else {
      claim = requests.claimOf(cr.id()).orElseThrow(ChangeRequestService::decided);
      if (!claim.checkerSub().equals(checker.subject())) {
        throw new MwException(ErrorCodes.VAL_REQUEST_DECIDED, "Another checker is approving this change request")
            .withStatus(HttpStatus.CONFLICT);
      }
      if (claim.claimedAt().plus(props.approvingLease()).isBefore(now)) {
        throw new MwException(ErrorCodes.VAL_REQUEST_DECIDED,
                "The approval's lease is over; it is resolved from mw-crypto's state shortly")
            .withStatus(HttpStatus.CONFLICT);
      }
      Boolean touched = tx.execute(s -> requests.touchAttempt(cr.id(), claim, now));
      if (!Boolean.TRUE.equals(touched)) {
        throw decided();
      }
    }
    String correlationId = correlationId();
    ResourceChangeHandler.Approval approval;
    try {
      approval = h.prepare(cr, checker, now, correlationId, claim.token());
    } catch (MwException e) {
      if (!e.status().is4xxClientError()) {
        throw e; // no answer: the claim stays APPROVING for a retry or the reconciler
      }
      // a refusal may answer a retry of a change already applied: settle the attempt first, which
      // also makes "not applied" binding at the other service
      Optional<Map<String, Object>> applied;
      try {
        applied = h.settle(cr, claim.token());
      } catch (MwException unknown) {
        throw e;
      }
      if (applied.isPresent()) {
        finishClaimed(cr, claim, applied.get(), h, now, correlationId);
        return found(cr.id());
      }
      giveUp(cr, claim, h, now, correlationId, false);
      throw e;
    }
    tx.executeWithoutResult(
        s -> {
          Map<String, Object> payload = new LinkedHashMap<>(approval.payload());
          payload.putAll(approval.apply().get());
          if (!requests.finishApproved(cr.id(), claim, payload, now)) {
            throw decided();
          }
          audit(ChangeRequestMapper.approved(cr, claim.checkerId(), approval.sentAs(), correlationId, now), cr);
        });
    logDecision("admin.change_request.approved", cr, claim.checkerId());
    return found(cr.id());
  }

  /**
   * After the other service cancelled the attempt: back to PENDING (a new attempt id later), or
   * EXPIRED when {@code expiresAt} passed ({@code expireDue} true) or the request cannot be approved
   * again ({@link ResourceChangeHandler#reusableAfterCancel}).
   */
  private void giveUp(
      ChangeRequest cr, ChangeRequestRepository.Claim claim, ResourceChangeHandler h, Instant now, String correlationId,
      boolean expireDue) {
    if ((expireDue && cr.expiredAt(now)) || !h.reusableAfterCancel(cr)) {
      tx.executeWithoutResult(
          s -> {
            if (requests.expireClaimed(cr.id(), claim, now)) {
              closed(cr, ChangeRequest.Status.EXPIRED, null, now);
              audit(ChangeRequestMapper.expired(cr, correlationId, now), cr);
            }
          });
      logDecision("admin.change_request.expired", cr, null);
    } else {
      tx.executeWithoutResult(s -> requests.release(cr.id(), claim));
      logDecision("admin.change_request.released", cr, claim.checkerId());
    }
  }

  /** APPROVING -> APPROVED for a change the other service reports applied, with its audit. */
  private void finishClaimed(
      ChangeRequest cr, ChangeRequestRepository.Claim claim, Map<String, Object> added, ResourceChangeHandler h,
      Instant now, String correlationId) {
    tx.executeWithoutResult(
        s -> {
          Map<String, Object> payload = new LinkedHashMap<>(cr.payload());
          payload.putAll(added);
          if (!requests.finishApproved(cr.id(), claim, payload, now)) {
            throw decided();
          }
          audit(ChangeRequestMapper.approved(cr, claim.checkerId(), h.resourceType().equals(ChangeRequest.KEY)
              ? "mw-crypto" : h.resourceType(), correlationId, now), cr);
        });
    logDecision("admin.change_request.approved", cr, claim.checkerId());
  }

  /**
   * Resolves APPROVING requests whose last attempt is older than the lease plus the client read
   * timeout (ticket 75; {@code scheduler/ApprovingReconcileJob}): the attempt is settled at the other
   * service (cancelled unless applied, so a call still in flight cannot apply afterwards); applied ->
   * APPROVED, cancelled -> EXPIRED once {@code expiresAt} passed (or not reusable), else PENDING;
   * cannot tell -> next run. Then each handler's own reconcile (orphan ceremonies). Returns how many
   * requests were resolved.
   */
  public int reconcileApproving() {
    Instant now = clock.instant();
    int n = 0;
    for (ChangeRequest cr : requests.staleApproving(now.minus(attemptGrace), props.expiryBatch())) {
      ResourceChangeHandler h = handlers.get(cr.resourceType());
      Optional<ChangeRequestRepository.Claim> claim = requests.claimOf(cr.id());
      if (h == null || claim.isEmpty()) {
        continue;
      }
      Optional<Map<String, Object>> applied;
      try {
        applied = h.settle(cr, claim.get().token());
      } catch (MwException e) {
        log.atWarn()
            .addKeyValue("event", "admin.change_request.reconcile_deferred")
            .addKeyValue("changeRequestId", cr.id().toString())
            .addKeyValue("errorCode", e.code())
            .log("cannot settle the claimed change; next run");
        continue;
      }
      String correlationId = correlationId();
      if (applied.isPresent()) {
        finishClaimed(cr, claim.get(), applied.get(), h, now, correlationId);
      } else {
        giveUp(cr, claim.get(), h, now, correlationId, true);
      }
      n++;
    }
    Instant olderThan = now.minus(attemptGrace);
    for (ResourceChangeHandler h : handlers.values()) {
      try {
        h.reconcile(requests::find, olderThan);
      } catch (MwException e) {
        log.atWarn()
            .addKeyValue("event", "admin.change_request.reconcile_deferred")
            .addKeyValue("resourceType", h.resourceType())
            .addKeyValue("errorCode", e.code())
            .log("handler reconcile failed; next run");
      }
    }
    return n;
  }

  /**
   * Approval of a wallet unlock, one transaction: row APPROVED + {@code auth.wallet-lock.v1} UNLOCK
   * (mw-auth's PIN and ADMIN locks, ticket 70) + {@code UnlockWallet} to the core (its own LOCKED
   * status, ticket 52; kept while the core still locks on wrong PINs) + audit ({@code sentAs} both).
   */
  private ChangeRequestView approveWallet(Operator checker, ChangeRequest cr) {
    Instant now = clock.instant();
    String correlationId = correlationId();
    UnlockWalletCommand cmd = ChangeRequestMapper.unlock(cr, checker.username(), correlationId, now, props.unlockCommandTtl());
    WalletLockCommand lock = ChangeRequestMapper.authUnlock(cr, checker.username(), correlationId, now);
    Map<String, Object> payload = new LinkedHashMap<>(cr.payload());
    payload.put("commandId", cmd.commandId());
    payload.put("authCommandId", lock.commandId());
    tx.executeWithoutResult(
        s -> {
          if (!requests.decide(cr.id(), ChangeRequest.Status.APPROVED, checker.username(), checker.subject(), null, payload, now)) {
            throw decided();
          }
          outbox.append(WALLET_LOCK_TOPIC, lock.walletId(),
              new EventMetadata(correlationId, cmd.txnId(), lock.commandId(), 1), lock);
          outbox.append(CORE_COMMAND_TOPIC, cmd.txnId(), new EventMetadata(correlationId, cmd.txnId(), cmd.commandId(), 1), cmd);
          audit(ChangeRequestMapper.approved(cr, checker.username(), WALLET_LOCK_TOPIC + "," + CORE_COMMAND_TOPIC,
              correlationId, now), cr);
        });
    logDecision("admin.change_request.approved", cr, checker.username());
    return found(cr.id());
  }

  /**
   * Approval of a care PIN reset (ticket 70; security review: no remote call before the decision),
   * one transaction like every approval (spec 6.4): the conditional PENDING -> APPROVED update claims
   * the row, then PIN_RESET on {@code auth.wallet-lock.v1} through the outbox ({@code
   * payload.authCommandId}) and the audit. mw-auth opens the reset only from that committed command,
   * so a reject or an expiry that wins the race leaves nothing to undo.
   */
  private ChangeRequestView approvePinReset(Operator checker, ChangeRequest cr) {
    Instant now = clock.instant();
    String correlationId = correlationId();
    WalletLockCommand cmd = ChangeRequestMapper.pinReset(cr, checker.username(), correlationId, now);
    Map<String, Object> payload = new LinkedHashMap<>(cr.payload());
    payload.put("authCommandId", cmd.commandId());
    tx.executeWithoutResult(
        s -> {
          if (!requests.decide(cr.id(), ChangeRequest.Status.APPROVED, checker.username(), checker.subject(), null, payload, now)) {
            throw decided();
          }
          outbox.append(WALLET_LOCK_TOPIC, cmd.walletId(),
              new EventMetadata(correlationId, ChangeRequestMapper.unlockTxnId(cr), cmd.commandId(), 1), cmd);
          audit(ChangeRequestMapper.approved(cr, checker.username(), WALLET_LOCK_TOPIC, correlationId, now), cr);
        });
    logDecision("admin.change_request.approved", cr, checker.username());
    return found(cr.id());
  }

  public ChangeRequestView reject(Operator checker, UUID id, String reason) {
    ChangeRequest cr = decidable(checker, id, false);
    Instant now = clock.instant();
    tx.executeWithoutResult(
        s -> {
          if (!requests.decide(id, ChangeRequest.Status.REJECTED, checker.username(), checker.subject(), reason, cr.payload(), now)) {
            throw decided();
          }
          closed(cr, ChangeRequest.Status.REJECTED, checker.username(), now);
          audit(ChangeRequestMapper.rejected(cr, checker.username(), reason, correlationId(), now), cr);
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

  /**
   * One request. Transaction and wallet requests are shown only to transaction readers (ticket
   * 37's least privilege); integration staff (ticket 38) see partner requests only.
   */
  public ChangeRequestView get(Operator operator, UUID id) {
    requireReader(operator);
    ChangeRequest cr = requests.find(id).orElseThrow(ChangeRequestService::notFound);
    Set<String> visible = visibleTypes(operator);
    if (visible != null && !visible.contains(cr.resourceType())) {
      throw new ForbiddenException("This operation needs a transaction viewing role");
    }
    return ChangeRequestMapper.view(cr);
  }

  /**
   * Newest first; an operator who may not read transactions sees partner requests (partner
   * readers) and key requests (key readers, ticket 75) only.
   */
  public ChangeRequestView.Page list(
      Operator operator, ChangeRequestView.Status shown, String resourceType, String resourceId, int limit, int offset) {
    requireReader(operator);
    ChangeRequest.Status status = ChangeRequestMapper.status(shown);
    Set<String> visible = visibleTypes(operator);
    List<ChangeRequest> rows;
    if (visible == null) {
      rows = requests.list(status, resourceType, resourceId, limit, offset);
    } else if (resourceType != null && !visible.contains(resourceType)) {
      throw new ForbiddenException("This operation needs a transaction viewing role");
    } else {
      rows = requests.listTypes(status, resourceType == null ? visible : Set.of(resourceType), resourceId, limit, offset);
    }
    return new ChangeRequestView.Page(rows.stream().map(ChangeRequestMapper::view).toList(), limit, offset);
  }

  /** The resource types an operator may read, null for all (transaction readers). */
  private static Set<String> visibleTypes(Operator operator) {
    if (operator.hasAny(AdminRole.READ_TRANSACTIONS)) {
      return null;
    }
    Set<String> out = new java.util.LinkedHashSet<>();
    if (operator.hasAny(AdminRole.READ_PARTNERS)) {
      out.add(Partner.RESOURCE_TYPE);
    }
    if (operator.hasAny(AdminRole.READ_CHANNEL_CLIENTS)) {
      out.add(mfs.mw.admin.api.entity.ChannelClient.RESOURCE_TYPE);
    }
    if (operator.hasAny(AdminRole.READ_KEYS)) {
      out.add(ChangeRequest.KEY);
    }
    return out;
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

  /**
   * A request this checker may decide now; an expired one is marked EXPIRED first, then refused. An
   * APPROVING request (claim-first types) may only be approved again (a retry), never rejected.
   */
  private ChangeRequest decidable(Operator checker, UUID id, boolean approving) {
    if (!checker.hasAny(AdminRole.ANY_CHECKER)) {
      throw new ForbiddenException("Deciding a change request needs a checker role");
    }
    ChangeRequest cr = requests.find(id).orElseThrow(ChangeRequestService::notFound);
    Set<AdminRole> checkers =
        ChangeRequest.TRANSACTION.equals(cr.resourceType()) || ChangeRequest.walletLike(cr.resourceType())
            ? AdminRole.TXN_CHECKER
            : handler(cr.resourceType()).checkers();
    if (!checker.hasAny(checkers)) {
      throw new ForbiddenException("Deciding this change request needs another checker role");
    }
    if (cr.makerSub().equals(checker.subject()) || cr.makerId().equals(checker.username())) {
      log.atWarn()
          .addKeyValue("event", "admin.change_request.own_decision_refused")
          .addKeyValue("errorCode", ErrorCodes.AUTH_OWN_REQUEST)
          .log("maker tried to decide own change request");
      throw new MwException(ErrorCodes.AUTH_OWN_REQUEST, "The maker of a change request cannot decide it")
          .withStatus(HttpStatus.FORBIDDEN);
    }
    ResourceChangeHandler typeHandler = handlers.get(cr.resourceType());
    if (typeHandler != null) {
      typeHandler.checkDecider(cr, checker, clock.instant());
    }
    if (cr.status() == ChangeRequest.Status.EXPIRED) {
      throw expired();
    }
    if (cr.status() == ChangeRequest.Status.APPROVING) {
      if (approving && typeHandler != null && typeHandler.claimFirst()) {
        return cr;
      }
      throw new MwException(ErrorCodes.VAL_REQUEST_DECIDED, "The change request is being approved")
          .withStatus(HttpStatus.CONFLICT);
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

  /** Stores a new PENDING request with its creation audit; one open request per resource. */
  private void insert(ChangeRequest cr, String openMessage) {
    tx.executeWithoutResult(
        s -> {
          if (!requests.insert(cr)) {
            throw new MwException(ErrorCodes.VAL_REQUEST_OPEN, openMessage).withStatus(HttpStatus.CONFLICT);
          }
          audit(ChangeRequestMapper.created(cr, correlationId()), cr);
        });
  }

  private void expireOne(ChangeRequest cr, Instant now) {
    if (requests.decide(cr.id(), ChangeRequest.Status.EXPIRED, null, null, null, cr.payload(), now)) {
      closed(cr, ChangeRequest.Status.EXPIRED, null, now);
      audit(ChangeRequestMapper.expired(cr, correlationId(), now), cr);
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

  /** Key the txnId (transactions) or the resource; the 6.1 txn header is the change request id when there is no txn. */
  private void audit(AuditEvent e, ChangeRequest cr) {
    String key = e.txnId() != null ? e.txnId() : e.resource();
    String txn = e.txnId() != null ? e.txnId() : cr.id().toString();
    outbox.append(AUDIT_TOPIC, key, new EventMetadata(e.correlationId(), txn, e.auditId(), 1), e);
  }

  /** Tells the resource type's handler (if any) that its request was rejected or expired. */
  private void closed(ChangeRequest cr, ChangeRequest.Status status, String checkerId, Instant now) {
    ResourceChangeHandler h = handlers.get(cr.resourceType());
    if (h != null) {
      h.closed(cr, status, checkerId, now);
    }
  }

  private ResourceChangeHandler handler(String resourceType) {
    ResourceChangeHandler h = handlers.get(resourceType);
    if (h == null) {
      throw new IllegalStateException("no change request handler for resource type " + resourceType);
    }
    return h;
  }

  private ChangeRequestView found(UUID id) {
    return requests.find(id).map(ChangeRequestMapper::view).orElseThrow(ChangeRequestService::notFound);
  }

  private static void requireReader(Operator operator) {
    if (!operator.hasAny(AdminRole.READ_CHANGE_REQUESTS)) {
      throw new ForbiddenException("This operation needs a viewing role");
    }
  }

  private static String correlationId() {
    return CorrelationIds.current();
  }

  private static void logDecision(String event, ChangeRequest cr, String checker) {
    log.atInfo()
        .addKeyValue("event", event)
        .addKeyValue(
            ChangeRequest.walletLike(cr.resourceType())
                ? "walletId"
                : ChangeRequest.TRANSACTION.equals(cr.resourceType()) ? "txnId" : "resourceId",
            ChangeRequestMapper.shownId(cr))
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
