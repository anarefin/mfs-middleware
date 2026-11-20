package mfs.mw.recon.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.recon.dto.ExceptionView;
import mfs.mw.recon.exception.ErrorCodes;
import mfs.mw.recon.exception.MwException;
import mfs.mw.recon.messaging.EventMetadata;
import mfs.mw.recon.messaging.OutboxWriter;
import mfs.mw.recon.repository.ReconStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The exception work queue the console workbench reads (ticket 127, UC-22). Reading and explaining only: an
 * action (force complete, reverse, raise dispute) is a maker-checker change request in mw-admin-api.
 */
@Service
public class ExceptionQueueService {

  static final int MAX_LIMIT = 500;

  private static final Logger log = LoggerFactory.getLogger(ExceptionQueueService.class);

  private final ReconStore store;
  private final OutboxWriter outbox;
  private final Clock clock;
  private final ExceptionEvents events;

  public ExceptionQueueService(ReconStore store, OutboxWriter outbox, Clock clock, ExceptionEvents events) {
    this.events = events;
    this.store = store;
    this.outbox = outbox;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<ExceptionView> list(String reconClass, String partnerId, String state, Integer minAgeHours, int limit) {
    Instant now = clock.instant();
    Instant olderThan = minAgeHours == null ? null : now.minus(Duration.ofHours(minAgeHours));
    int bounded = Math.clamp(limit, 1, MAX_LIMIT);
    return store.exceptionQueue(blankToNull(reconClass), blankToNull(partnerId), blankToNull(state), olderThan, bounded)
        .stream().map(row -> view(row, now)).toList();
  }

  @Transactional(readOnly = true)
  public ExceptionView get(UUID id) {
    return view(require(id), clock.instant());
  }

  /**
   * Records the explanation of an OPEN or EXPLAINED exception (it becomes EXPLAINED) and audits it on
   * {@code mw.audit.event.v1} in the same transaction. An ACTIONED or RESOLVED exception is refused with 409.
   */
  @Transactional
  public ExceptionView explain(UUID id, String explanation, String operatorId) {
    require(id);
    Instant now = clock.instant();
    if (store.explain(id, explanation.strip(), operatorId, now) == 0) {
      throw new MwException(ErrorCodes.VAL_STATE_CONFLICT, "the exception is closed and cannot be explained")
          .withStatus(HttpStatus.CONFLICT);
    }
    String correlation = MDC.get("correlationId");
    if (correlation == null || correlation.isBlank()) {
      correlation = "recon-" + id;
    }
    String auditId = UUID.randomUUID().toString();
    outbox.append(ReconTopics.AUDIT, operatorId, new EventMetadata(correlation, id.toString(), "explain-" + auditId, 1),
        audit(auditId, correlation, id, operatorId, now));
    events.changed(id, operatorId, now);
    log.atInfo().addKeyValue("event", "recon.exception.explained").addKeyValue("exceptionId", id.toString())
        .log("recon exception explained");
    return get(id);
  }

  /**
   * Records an action a checker approved in mw-admin-api (ticket 127b): OPEN or EXPLAINED -> ACTIONED, waiting for
   * the transaction to reach COMPLETED (FORCE_COMMIT) or REVERSED (REVERSE) on {@code mw.txn.status.v1}; when the
   * projection already shows that status it is RESOLVED at once. Idempotent on the change request id; another action
   * on an ACTIONED or RESOLVED exception, or an exception without a transaction, is refused with 409.
   */
  @Transactional
  public ExceptionView action(UUID id, String action, UUID changeRequestId, String operatorId) {
    ReconStore.ExceptionRow row = require(id);
    if (changeRequestId.equals(row.changeRequestId())) {
      return get(id);
    }
    Instant now = clock.instant();
    String expected = expectedStatus(action);
    if (store.markActioned(id, action, changeRequestId, operatorId, expected, now) == 0) {
      throw new MwException(ErrorCodes.VAL_STATE_CONFLICT, row.txnId() == null
          ? "the exception has no transaction to act on" : "the exception is already actioned or resolved")
          .withStatus(HttpStatus.CONFLICT);
    }
    events.changed(id, operatorId, now);
    log.atInfo().addKeyValue("event", "recon.exception.actioned").addKeyValue("exceptionId", id.toString())
        .addKeyValue("action", action).addKeyValue("changeRequestId", changeRequestId.toString())
        .log("recon exception actioned");
    if (expected.equals(store.txnState(row.txnId())) && store.resolve(id, now) == 1) {
      events.changed(id, null, now);
    }
    return get(id);
  }

  /** The transaction status that shows an action worked. */
  static String expectedStatus(String action) {
    return switch (action) {
      case "FORCE_COMMIT" -> "COMPLETED";
      case "REVERSE" -> "REVERSED";
      default -> throw new MwException(ErrorCodes.VAL_INVALID, "unknown action " + action);
    };
  }

  private static Map<String, Object> audit(String auditId, String correlation, UUID id, String operatorId,
      Instant at) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("schemaVersion", 1);
    body.put("auditId", auditId);
    body.put("correlationId", correlation);
    body.put("actor", Map.of("type", "user", "id", operatorId));
    body.put("action", "recon.exception.explained");
    body.put("resource", "recon_exception/" + id);
    body.put("outcome", "SUCCESS");
    body.put("at", at.toString());
    body.put("service", "mw-recon");
    return body;
  }

  private ReconStore.ExceptionRow require(UUID id) {
    ReconStore.ExceptionRow row = store.exception(id);
    if (row == null) {
      throw new MwException(ErrorCodes.VAL_NOT_FOUND, "no such recon exception").withStatus(HttpStatus.NOT_FOUND);
    }
    return row;
  }

  /**
   * Advice for the workbench. A missing core posting suggests FORCE_COMPLETE, a completed transaction the partner
   * does not carry suggests REVERSE, money the partner reports without a Upay transaction or a wrong amount suggests
   * RAISE_DISPUTE, and a trust position gap is investigated and then accepted as explained.
   */
  static String suggestedAction(String reconClass, String detail) {
    if (TrustPositionService.DETAIL.equals(detail)) {
      return "ACCEPT_AS_EXPLAINED";
    }
    return switch (reconClass) {
      case "STATUS_MISMATCH" -> "FORCE_COMPLETE";
      case "MISSING_IN_PARTNER" -> "REVERSE";
      case "MISSING_IN_UPAY", "AMOUNT_MISMATCH" -> "RAISE_DISPUTE";
      default -> "ACCEPT_AS_EXPLAINED";
    };
  }

  private static ExceptionView view(ReconStore.ExceptionRow row, Instant now) {
    long age = Math.max(0, Duration.between(row.createdAt(), now).toHours());
    return new ExceptionView(row.id().toString(), row.reconClass(), row.detail(), row.owner(), row.partnerId(),
        row.txnId() == null ? null : row.txnId().toString(), row.partnerRef(), row.reference(), row.fileAmountMinor(),
        row.upayAmountMinor(), row.ledgerAmountMinor(), row.state(), row.createdAt(), age,
        suggestedAction(row.reconClass(), row.detail()), row.explanation(), row.explainedBy(), row.explainedAt(),
        row.action(), row.changeRequestId() == null ? null : row.changeRequestId().toString(), row.actionedBy());
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
