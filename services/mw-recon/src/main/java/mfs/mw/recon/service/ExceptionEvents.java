package mfs.mw.recon.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import mfs.mw.recon.messaging.EventMetadata;
import mfs.mw.recon.messaging.OutboxWriter;
import mfs.mw.recon.repository.ReconStore;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * {@code mw.recon.exception.v1} (ticket 127b, FRS DAT-01 {@code recon.exception}): one event per state change of an
 * exception (OPEN, EXPLAINED, ACTIONED, RESOLVED), written to the outbox in the caller's transaction, key the
 * exception id. Read back from the row so the event is what was stored.
 */
@Component
public class ExceptionEvents {

  private final ReconStore store;
  private final OutboxWriter outbox;

  public ExceptionEvents(ReconStore store, OutboxWriter outbox) {
    this.store = store;
    this.outbox = outbox;
  }

  /** Appends the event of the exception's current state; {@code actor} is null for a system change. */
  public void changed(UUID exceptionId, String actor, Instant at) {
    ReconStore.ExceptionRow row = store.exception(exceptionId);
    if (row == null) {
      return;
    }
    String correlation = MDC.get("correlationId");
    if (correlation == null || correlation.isBlank()) {
      correlation = "recon-" + exceptionId;
    }
    String eventId = UUID.randomUUID().toString();
    String txn = row.txnId() == null ? exceptionId.toString() : row.txnId().toString();
    outbox.append(ReconTopics.EXCEPTION, exceptionId.toString(), new EventMetadata(correlation, txn, eventId, 1),
        body(row, eventId, actor, correlation, at));
  }

  static Map<String, Object> body(ReconStore.ExceptionRow row, String eventId, String actor, String correlation,
      Instant at) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("schemaVersion", 1);
    body.put("eventId", eventId);
    body.put("exceptionId", row.id().toString());
    body.put("state", row.state());
    body.put("exceptionClass", row.reconClass());
    if (row.detail() != null && !row.detail().isBlank()) {
      body.put("detail", row.detail());
    }
    body.put("owner", row.owner());
    body.put("partnerId", row.partnerId() == null ? "unknown" : row.partnerId());
    if (row.txnId() != null) {
      body.put("txnId", row.txnId().toString());
    }
    body.put("amountMinor", Math.abs(firstNonNull(row.fileAmountMinor(), row.upayAmountMinor(),
        row.ledgerAmountMinor())));
    body.put("statementRef", firstText(row.partnerRef(), row.reference(), row.id().toString()));
    if (row.action() != null) {
      body.put("action", row.action());
    }
    if (row.changeRequestId() != null) {
      body.put("changeRequestId", row.changeRequestId().toString());
    }
    if (actor != null && !actor.isBlank()) {
      body.put("actor", actor);
    }
    body.put("detectedAt", row.createdAt().toString());
    body.put("changedAt", at.toString());
    body.put("correlationId", correlation);
    return body;
  }

  private static long firstNonNull(Long... values) {
    for (Long v : values) {
      if (v != null) {
        return v;
      }
    }
    return 0L;
  }

  private static String firstText(String... values) {
    for (String v : values) {
      if (v != null && !v.isBlank()) {
        return v;
      }
    }
    return "";
  }
}
