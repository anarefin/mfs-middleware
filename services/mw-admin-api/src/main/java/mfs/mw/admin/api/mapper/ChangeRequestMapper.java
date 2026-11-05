package mfs.mw.admin.api.mapper;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.admin.api.dto.AdminTxn;
import mfs.mw.admin.api.dto.AuditEvent;
import mfs.mw.admin.api.dto.ChangeRequestView;
import mfs.mw.admin.api.dto.ReversalRequest;
import mfs.mw.admin.api.dto.TxnAction;
import mfs.mw.admin.api.dto.UnlockWalletCommand;
import mfs.mw.admin.api.entity.ChangeRequest;
import mfs.mw.admin.api.util.MaskingPolicy;
import mfs.mw.admin.api.util.SensitiveType;

/**
 * Change requests (ticket 37, spec 15.3) to their {@code admin.v1} view, their audit events on
 * {@code mw.audit.event.v1} and the messages an approval sends to mw-orchestrator. Audit: action
 * {@code admin.change_request.<created|approved|rejected|expired>}, resource {@code
 * transaction/<txnId>} (so the request shows in the transaction's audit trail), {@code before} /
 * {@code after} the request status and the transaction status; reasons are scrubbed free text. A
 * wallet request (UNLOCK, ticket 52) is audited as {@code wallet/<masked MSISDN>} with txnId its
 * {@code unlockTxnId} and {@code walletStatus} in place of {@code txnStatus}; its approval sends
 * {@code UnlockWallet} to the core. A partner request (ticket 38) is audited as {@code
 * partner/<id>} without a txnId; its before/after carry {@code config} (the changed fields of the
 * diff, never secrets) in place of a status.
 */
public final class ChangeRequestMapper {

  public static final String SERVICE = "mw-admin-api";
  public static final String CREATED = "admin.change_request.created";
  public static final String APPROVED = "admin.change_request.approved";
  public static final String REJECTED = "admin.change_request.rejected";
  public static final String EXPIRED = "admin.change_request.expired";
  /** Payload key of a wallet request's txnId (ticket 52). */
  public static final String UNLOCK_TXN_ID = "unlockTxnId";

  /** The organisation a REVERSAL names as its parties (no customer data in the request). */
  static final ReversalRequest.Party OPS = new ReversalRequest.Party("ORG", "upay-ops");

  private ChangeRequestMapper() {}

  /**
   * The {@code admin.v1} view. Operators' free text (reason, decision reason, every string in payload
   * and diff) is scrubbed ({@link MaskingPolicy#scrub}): a customer's number typed into a reason is
   * shown masked to every reader; change requests have no reveal path.
   */
  public static ChangeRequestView view(ChangeRequest cr) {
    return new ChangeRequestView(
        cr.id().toString(), cr.resourceType(), shownId(cr), cr.action(), scrubbed(cr.payload()),
        scrubbed(cr.diff()), cr.makerId(), cr.status().name(), cr.checkerId(), MaskingPolicy.scrub(cr.reason()),
        MaskingPolicy.scrub(cr.decisionReason()), cr.createdAt(), cr.decidedAt(), cr.expiresAt());
  }

  private static final java.util.regex.Pattern UUID_SHAPE =
      java.util.regex.Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private static Map<String, Object> scrubbed(Map<String, Object> map) {
    Map<String, Object> out = new LinkedHashMap<>();
    map.forEach((k, v) -> out.put(k, scrubbedValue(v)));
    return out;
  }

  private static Object scrubbedValue(Object value) {
    if (value instanceof String s) {
      // a whole UUID is a system id this service wrote (actionId, reversalTxnId, unlockTxnId)
      return UUID_SHAPE.matcher(s).matches() ? s : MaskingPolicy.scrub(s);
    }
    if (value instanceof Map<?, ?> m) {
      Map<Object, Object> out = new LinkedHashMap<>();
      m.forEach((k, v) -> out.put(k, scrubbedValue(v)));
      return out;
    }
    if (value instanceof List<?> l) {
      return l.stream().map(ChangeRequestMapper::scrubbedValue).toList();
    }
    return value;
  }

  /** {@code diff}: the status the maker saw and the one the action leads to. */
  public static Map<String, Object> diff(String before, String after) {
    return Map.of("before", Map.of("status", before), "after", Map.of("status", after));
  }

  public static AuditEvent created(ChangeRequest cr, String correlationId) {
    Map<String, Object> after = base(cr, "PENDING");
    after.put("expiresAt", cr.expiresAt().toString());
    after.put("reason", MaskingPolicy.scrub(cr.reason()));
    Map<String, Object> before;
    if (config(cr)) {
      before = Map.of("config", side(cr, "before"));
      after.put("config", side(cr, "after"));
    } else {
      before = Map.of(statusKey(cr), cr.statusBefore());
    }
    return event(cr, correlationId, "user", cr.makerId(), CREATED, before, after, cr.createdAt());
  }

  public static AuditEvent approved(ChangeRequest cr, String checkerId, String sentAs, String correlationId, Instant at) {
    Map<String, Object> after = base(cr, "APPROVED");
    if (config(cr)) {
      after.put("config", side(cr, "after"));
    } else {
      after.put(statusKey(cr), cr.statusAfter());
    }
    after.put("makerId", cr.makerId());
    after.put("sentAs", sentAs);
    return event(cr, correlationId, "user", checkerId, APPROVED, pendingBefore(cr), after, at);
  }

  public static AuditEvent rejected(ChangeRequest cr, String checkerId, String reason, String correlationId, Instant at) {
    Map<String, Object> after = base(cr, "REJECTED");
    if (!config(cr)) {
      after.put(statusKey(cr), cr.statusBefore());
    }
    after.put("makerId", cr.makerId());
    after.put("reason", MaskingPolicy.scrub(reason));
    return event(cr, correlationId, "user", checkerId, REJECTED, pendingBefore(cr), after, at);
  }

  public static AuditEvent expired(ChangeRequest cr, String correlationId, Instant at) {
    Map<String, Object> after = base(cr, "EXPIRED");
    after.put("makerId", cr.makerId());
    return event(cr, correlationId, "system", SERVICE, EXPIRED, pendingBefore(cr), after, at);
  }

  /** The approved non-reversal action for the orchestrator ({@code mw.txn.action.v1}). */
  public static TxnAction action(ChangeRequest cr, UUID actionId, String checkerId, String correlationId, Instant at) {
    return new TxnAction(
        1, actionId.toString(), cr.resourceId(), cr.action(), correlationId, cr.id().toString(), cr.makerId(), checkerId,
        MaskingPolicy.scrub(cr.reason()), at);
  }

  /**
   * The approved REVERSAL ({@code mw.txn.request.v1}): channel SYSTEM, the original's full
   * amount, idempotency key the change request id (a redelivery reverses nothing twice).
   */
  public static ReversalRequest reversal(
      ChangeRequest cr, UUID reversalTxnId, AdminTxn.Amount amount, String checkerId, String correlationId, Instant at) {
    return new ReversalRequest(
        1, reversalTxnId.toString(), cr.id().toString(), correlationId, "SYSTEM", "REVERSAL", OPS, OPS, OPS, amount,
        cr.resourceId(), at,
        Map.of("changeRequestId", cr.id().toString(), "makerId", cr.makerId(), "checkerId", checkerId));
  }

  /**
   * The approved wallet unlock for the core ({@code UnlockWallet} on {@code core.command.v1},
   * ticket 52): txnId the request's {@code unlockTxnId}, commandId {@code <changeRequestId>:unlock}.
   */
  public static UnlockWalletCommand unlock(
      ChangeRequest cr, String checkerId, String correlationId, Instant at, Duration ttl) {
    return new UnlockWalletCommand(
        1, UnlockWalletCommand.TYPE, unlockCommandId(cr), unlockTxnId(cr), correlationId, at, at.plus(ttl),
        new UnlockWalletCommand.Payload(
            new UnlockWalletCommand.Subject("WALLET", cr.resourceId()), cr.id().toString(), cr.makerId(), checkerId));
  }

  /** The core command id of a wallet unlock: the core is idempotent on it. */
  public static String unlockCommandId(ChangeRequest cr) {
    return cr.id() + ":unlock";
  }

  /** The UUIDv7 a wallet request carries as txnId (core command and audit), minted at creation. */
  public static String unlockTxnId(ChangeRequest cr) {
    return String.valueOf(cr.payload().get(UNLOCK_TXN_ID));
  }

  /** The resource id as views and audits show it: a wallet id (an MSISDN) masked. */
  public static String shownId(ChangeRequest cr) {
    return wallet(cr) ? MaskingPolicy.mask(SensitiveType.MSISDN, cr.resourceId()) : cr.resourceId();
  }

  private static boolean wallet(ChangeRequest cr) {
    return ChangeRequest.WALLET.equals(cr.resourceType());
  }

  private static String statusKey(ChangeRequest cr) {
    return wallet(cr) ? "walletStatus" : "txnStatus";
  }

  private static Map<String, Object> pendingBefore(ChangeRequest cr) {
    return config(cr)
        ? Map.of("status", "PENDING", "config", side(cr, "before"))
        : Map.of("status", "PENDING", statusKey(cr), cr.statusBefore());
  }

  /** A config resource (ticket 38: partner): no status, no txnId; the diff holds changed fields. */
  private static boolean config(ChangeRequest cr) {
    return !ChangeRequest.TRANSACTION.equals(cr.resourceType()) && !wallet(cr);
  }

  private static Object side(ChangeRequest cr, String key) {
    Object v = cr.diff().get(key);
    return v == null ? Map.of() : v;
  }

  private static Map<String, Object> base(ChangeRequest cr, String status) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("status", status);
    m.put("changeRequestId", cr.id().toString());
    m.put("action", cr.action());
    return m;
  }

  private static AuditEvent event(
      ChangeRequest cr, String correlationId, String actorType, String actorId, String action,
      Map<String, Object> before, Map<String, Object> after, Instant at) {
    return new AuditEvent(
        1,
        UUID.randomUUID().toString(),
        wallet(cr) ? unlockTxnId(cr) : config(cr) ? null : cr.resourceId(),
        correlationId == null || correlationId.isBlank() ? "admin-" + UUID.randomUUID() : correlationId,
        new AuditEvent.Actor(actorType, actorId),
        action,
        cr.resourceType() + "/" + shownId(cr),
        before,
        after,
        "SUCCESS",
        at,
        SERVICE);
  }
}
