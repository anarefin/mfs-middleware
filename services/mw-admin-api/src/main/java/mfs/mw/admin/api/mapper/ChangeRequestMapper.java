package mfs.mw.admin.api.mapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import mfs.mw.admin.api.dto.AdminTxn;
import mfs.mw.admin.api.dto.AuditEvent;
import mfs.mw.admin.api.dto.ChangeRequestView;
import mfs.mw.admin.api.dto.ReversalRequest;
import mfs.mw.admin.api.dto.TxnAction;
import mfs.mw.admin.api.entity.ChangeRequest;
import mfs.mw.admin.api.util.MaskingPolicy;

/**
 * Change requests (ticket 37, spec 15.3) to their {@code admin.v1} view, their audit events on
 * {@code mw.audit.event.v1} and the messages an approval sends to mw-orchestrator. Audit: action
 * {@code admin.change_request.<created|approved|rejected|expired>}, resource {@code
 * transaction/<txnId>} (so the request shows in the transaction's audit trail), {@code before} /
 * {@code after} the request status and the transaction status; reasons are scrubbed free text.
 */
public final class ChangeRequestMapper {

  public static final String SERVICE = "mw-admin-api";
  public static final String CREATED = "admin.change_request.created";
  public static final String APPROVED = "admin.change_request.approved";
  public static final String REJECTED = "admin.change_request.rejected";
  public static final String EXPIRED = "admin.change_request.expired";

  /** The organisation a REVERSAL names as its parties (no customer data in the request). */
  static final ReversalRequest.Party OPS = new ReversalRequest.Party("ORG", "upay-ops");

  private ChangeRequestMapper() {}

  public static ChangeRequestView view(ChangeRequest cr) {
    return new ChangeRequestView(
        cr.id().toString(), cr.resourceType(), cr.resourceId(), cr.action(), cr.payload(), cr.diff(), cr.makerId(),
        cr.status().name(), cr.checkerId(), cr.reason(), cr.decisionReason(), cr.createdAt(), cr.decidedAt(),
        cr.expiresAt());
  }

  /** {@code diff}: the status the maker saw and the one the action leads to. */
  public static Map<String, Object> diff(String before, String after) {
    return Map.of("before", Map.of("status", before), "after", Map.of("status", after));
  }

  public static AuditEvent created(ChangeRequest cr, String correlationId) {
    Map<String, Object> after = base(cr, "PENDING");
    after.put("expiresAt", cr.expiresAt().toString());
    after.put("reason", MaskingPolicy.scrub(cr.reason()));
    return event(cr, correlationId, "user", cr.makerId(), CREATED, Map.of("txnStatus", cr.statusBefore()), after,
        cr.createdAt());
  }

  public static AuditEvent approved(ChangeRequest cr, String checkerId, String sentAs, String correlationId, Instant at) {
    Map<String, Object> after = base(cr, "APPROVED");
    after.put("txnStatus", cr.statusAfter());
    after.put("makerId", cr.makerId());
    after.put("sentAs", sentAs);
    return event(cr, correlationId, "user", checkerId, APPROVED, pendingBefore(cr), after, at);
  }

  public static AuditEvent rejected(ChangeRequest cr, String checkerId, String reason, String correlationId, Instant at) {
    Map<String, Object> after = base(cr, "REJECTED");
    after.put("txnStatus", cr.statusBefore());
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

  private static Map<String, Object> pendingBefore(ChangeRequest cr) {
    return Map.of("status", "PENDING", "txnStatus", cr.statusBefore());
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
        cr.resourceId(),
        correlationId == null || correlationId.isBlank() ? "admin-" + UUID.randomUUID() : correlationId,
        new AuditEvent.Actor(actorType, actorId),
        action,
        cr.resourceType() + "/" + cr.resourceId(),
        before,
        after,
        "SUCCESS",
        at,
        SERVICE);
  }
}
