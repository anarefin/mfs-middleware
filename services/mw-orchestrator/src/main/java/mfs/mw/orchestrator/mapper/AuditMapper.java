package mfs.mw.orchestrator.mapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.orchestrator.dto.AuditEvent;
import mfs.mw.orchestrator.dto.CoreCommand;
import mfs.mw.orchestrator.dto.CoreReply;
import mfs.mw.orchestrator.dto.TxnAction;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.service.saga.Status;
import mfs.mw.orchestrator.util.AuditBodies;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * A saga state change to {@code mw.audit.event.v1} (spec 14.3): action {@code txn.state.changed},
 * resource {@code txn/<txnId>}, actor the orchestrator itself, {@code before}/{@code after} the
 * status (plus the error code when the new state carries one). A new transaction has no
 * {@code before}.
 */
@Component
public class AuditMapper {

  public static final String ACTION = "txn.state.changed";
  public static final String SERVICE = "mw-orchestrator";

  /** A partner callback that arrived after the transaction left PARTNER_PENDING / UNKNOWN. */
  public static final String LATE_CALLBACK = "partner.callback.late";

  /** A late callback whose outcome contradicts how the transaction was resolved. */
  public static final String OUTCOME_CONFLICT = "txn.outcome.conflict";

  /**
   * A late partner callback (ticket 22): recorded, the state stays. Actor is the partner;
   * {@code before} and {@code after} carry the unchanged status, {@code after} also the
   * callback's outcome (and error code). Outcome FAILURE when it contradicts the resolution.
   */
  public AuditEvent lateCallback(
      TransactionRecord rec, String partnerId, String correlationId, String callbackOutcome,
      String errorCode, boolean conflict, Instant at) {
    Map<String, String> after = new HashMap<>();
    after.put("status", rec.status().name());
    after.put("callbackOutcome", String.valueOf(callbackOutcome));
    if (errorCode != null) {
      after.put("errorCode", errorCode);
    }
    String txnId = rec.txnId().toString();
    return new AuditEvent(
        1,
        UUID.randomUUID().toString(),
        txnId,
        correlationId == null || correlationId.isBlank() ? rec.request().correlationId() : correlationId,
        new AuditEvent.Actor("partner", partnerId == null || partnerId.isBlank() ? "unknown" : partnerId),
        conflict ? OUTCOME_CONFLICT : LATE_CALLBACK,
        "txn/" + txnId,
        Map.of("status", rec.status().name()),
        after,
        conflict ? "FAILURE" : "SUCCESS",
        at,
        SERVICE);
  }

  /** The merchant system never took a payment notify (ticket 115b): the dead-letter record. */
  public static final String MERCHANT_NOTIFY_FAILED = "merchant.notify.failed";

  /**
   * A merchant payment notify that failed on every attempt (ticket 115b): outcome FAILURE, the
   * transaction's status unchanged, the attempts and the last error code. No parties or amounts.
   */
  public AuditEvent merchantNotifyFailed(
      TransactionRecord rec, String partnerId, int attempts, String errorCode, Instant at) {
    Map<String, String> after = new HashMap<>();
    after.put("status", rec.status().name());
    after.put("attempts", Integer.toString(attempts));
    if (errorCode != null) {
      after.put("errorCode", errorCode);
    }
    String txnId = rec.txnId().toString();
    return new AuditEvent(
        1,
        UUID.randomUUID().toString(),
        txnId,
        rec.request().correlationId(),
        new AuditEvent.Actor("partner", partnerId == null || partnerId.isBlank() ? "unknown" : partnerId),
        MERCHANT_NOTIFY_FAILED,
        "txn/" + txnId,
        Map.of("status", rec.status().name()),
        after,
        "FAILURE",
        at,
        SERVICE);
  }

  /** A callback that does not belong to the transaction (ownership check failed). */
  public static final String CALLBACK_REJECTED = "partner.callback.rejected";

  /**
   * A partner callback refused because it does not belong to the transaction: outcome DENIED,
   * {@code after} the unchanged status, the callback's outcome and the reason code.
   */
  public AuditEvent callbackRejected(
      TransactionRecord rec, String partnerId, String correlationId, String callbackOutcome, String reason,
      Instant at) {
    String txnId = rec.txnId().toString();
    return new AuditEvent(
        1,
        UUID.randomUUID().toString(),
        txnId,
        correlationId == null || correlationId.isBlank() ? rec.request().correlationId() : correlationId,
        new AuditEvent.Actor("partner", partnerId == null || partnerId.isBlank() ? "unknown" : partnerId),
        CALLBACK_REJECTED,
        "txn/" + txnId,
        Map.of("status", rec.status().name()),
        Map.of("status", rec.status().name(), "callbackOutcome", String.valueOf(callbackOutcome), "reason", reason),
        "DENIED",
        at,
        SERVICE);
  }

  /** One core REST call of a saga step (ticket 130b, FRS AUD-01, AUD-04). */
  public static final String CORE_CALL = "core.call.completed";

  /** The timeline hop of a core call (ticket 130b). */
  public static final String CORE_CALL_HOP = "CORE_CALL";

  private static final JsonMapper BODIES = JsonMapper.builder().build();

  /**
   * A core call and its answer: hop CORE_CALL, direction OUT, the call's latency, {@code after}
   * the command type, how definite the answer was, the HTTP status and a refusal's code; outcome
   * SUCCESS only for an answered call. Payloads: the command OUT and the answer IN (when there is
   * one, never for a BalanceInquiry), both masked by {@link AuditBodies} (the assertion blanked,
   * MSISDNs and names masked).
   */
  public AuditEvent coreCall(CoreCommand command, CoreReply reply, Instant at, long durationMs) {
    Map<String, String> after = new HashMap<>();
    after.put("commandType", command.commandType());
    after.put("kind", reply.kind().name());
    after.put("httpStatus", Integer.toString(reply.status()));
    if (command.commandId() != null) {
      after.put("commandId", command.commandId());
    }
    if (reply.code() != null) {
      after.put("code", reply.code());
    }
    List<AuditEvent.Payload> payload = new ArrayList<>();
    String request = AuditBodies.mask(BODIES.valueToTree(command));
    if (request != null) {
      payload.add(new AuditEvent.Payload("OUT", "application/json", request));
    }
    // a balance never leaves the COMPLETED status (ADR D26)
    String answer = "BalanceInquiry".equals(command.commandType()) ? null : AuditBodies.mask(reply.body());
    if (answer != null) {
      payload.add(new AuditEvent.Payload("IN", "application/json", answer));
    }
    String txnId = command.txnId();
    String correlationId = command.correlationId();
    return new AuditEvent(
        1,
        UUID.randomUUID().toString(),
        txnId,
        correlationId == null || correlationId.isBlank() ? txnId : correlationId,
        new AuditEvent.Actor("system", SERVICE),
        CORE_CALL,
        "txn/" + txnId,
        null,
        after,
        reply.kind() == CoreReply.Kind.ANSWERED ? "SUCCESS" : "FAILURE",
        at,
        SERVICE,
        CORE_CALL_HOP,
        "OUT",
        Math.max(0, durationMs),
        payload.isEmpty() ? null : payload);
  }

  public AuditEvent stateChanged(TransactionRecord rec, Status from, Instant at) {
    Map<String, String> after = new HashMap<>();
    after.put("status", rec.status().name());
    if (rec.error() != null) {
      after.put("errorCode", rec.error().code());
    }
    String txnId = rec.txnId().toString();
    return new AuditEvent(
        1,
        UUID.randomUUID().toString(),
        txnId,
        rec.request().correlationId(),
        new AuditEvent.Actor("system", SERVICE),
        ACTION,
        "txn/" + txnId,
        from == null ? null : Map.of("status", from.name()),
        after,
        "SUCCESS",
        at,
        SERVICE);
  }

  /** An operator action approved by maker-checker (ticket 37, {@code mw.txn.action.v1}). */
  public static final String OPERATOR_ACTION = "txn.operator.action";

  /**
   * An operator action applied or refused: actor the checker who approved it ({@code user}, staff
   * id), {@code before} the status the action found, {@code after} the status it left plus the
   * action, the maker, the change request and, when refused, {@code refused} = the error code
   * (outcome FAILURE). The maker's free-text reason is not copied: it may name a customer.
   */
  public AuditEvent operatorAction(
      TransactionRecord rec, TxnAction action, Status before, Status after, String refusedCode, Instant at) {
    Map<String, String> out = new HashMap<>();
    out.put("status", after.name());
    out.put("action", action.action());
    out.put("makerId", action.makerId());
    out.put("changeRequestId", action.changeRequestId());
    if (refusedCode != null) {
      out.put("refused", refusedCode);
    }
    String txnId = rec.txnId().toString();
    return new AuditEvent(
        1,
        UUID.randomUUID().toString(),
        txnId,
        action.correlationId() == null || action.correlationId().isBlank()
            ? rec.request().correlationId()
            : action.correlationId(),
        new AuditEvent.Actor("user", action.checkerId()),
        OPERATOR_ACTION,
        "txn/" + txnId,
        Map.of("status", before.name()),
        out,
        refusedCode == null ? "SUCCESS" : "FAILURE",
        at,
        SERVICE);
  }
}
