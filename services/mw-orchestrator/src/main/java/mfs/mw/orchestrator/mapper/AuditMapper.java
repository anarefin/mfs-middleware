package mfs.mw.orchestrator.mapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import mfs.mw.orchestrator.dto.AuditEvent;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.service.saga.Status;
import org.springframework.stereotype.Component;

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
}
