package mfs.mw.audit.dw.service;

import java.util.Locale;
import mfs.mw.audit.dw.dto.AuditEvent;
import mfs.mw.audit.dw.dto.ReconExceptionEvent;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes one {@code mw.recon.exception.v1} state change (ticket 127b, FRS DAT-01 recon.exception) into the audit
 * trail, in the same hash chain as {@code mw.audit.event.v1}: action {@code recon.exception.<state>}, resource
 * {@code recon_exception/<exceptionId>}, actor the operator ({@code user}) when the event names one, else {@code
 * system}/{@code mw-recon}; {@code after} carries the state, class, detail, action and change request. The audit id
 * is the event's {@code eventId}, so a redelivery is stored once ({@code audit.event_key}).
 */
@Service
public class ReconExceptionAudit {

  static final String SERVICE = "mw-recon";

  private final AuditService audit;
  private final JsonMapper json;

  public ReconExceptionAudit(AuditService audit, JsonMapper json) {
    this.audit = audit;
    this.json = json;
  }

  /** @return true when stored, false for a redelivery */
  public boolean record(ReconExceptionEvent event) {
    return audit.record(toAudit(event, json));
  }

  /** @throws IllegalArgumentException when a field the mapping needs is missing (the record goes to the DLQ) */
  static AuditEvent toAudit(ReconExceptionEvent e, JsonMapper json) {
    if (e.eventId() == null || e.exceptionId() == null) {
      throw new IllegalArgumentException("recon exception event lacks eventId or exceptionId");
    }
    if (e.state() == null || e.state().isBlank()) {
      throw new IllegalArgumentException("recon exception event lacks state");
    }
    if (e.changedAt() == null) {
      throw new IllegalArgumentException("recon exception event lacks changedAt");
    }
    boolean byOperator = e.actor() != null && !e.actor().isBlank();
    AuditEvent.Actor actor = byOperator ? new AuditEvent.Actor("user", e.actor()) : new AuditEvent.Actor("system", SERVICE);
    ObjectNode after = json.createObjectNode();
    after.put("state", e.state());
    putIfPresent(after, "exceptionClass", e.exceptionClass());
    putIfPresent(after, "detail", e.detail());
    putIfPresent(after, "owner", e.owner());
    putIfPresent(after, "partnerId", e.partnerId());
    putIfPresent(after, "action", e.action());
    putIfPresent(after, "changeRequestId", e.changeRequestId() == null ? null : e.changeRequestId().toString());
    if (e.amountMinor() != null) {
      after.put("amountMinor", e.amountMinor());
    }
    return new AuditEvent(
        1, e.eventId(), e.txnId() == null ? null : e.txnId().toString(), e.correlationId(), actor,
        "recon.exception." + e.state().toLowerCase(Locale.ROOT), "recon_exception/" + e.exceptionId(), null, after,
        null, "SUCCESS", e.changedAt(), null, SERVICE);
  }

  private static void putIfPresent(ObjectNode node, String name, String value) {
    if (value != null && !value.isBlank()) {
      node.put(name, value);
    }
  }
}
