package mfs.mw.admin.api.mapper;

import mfs.mw.admin.api.util.CorrelationIds;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.admin.api.dto.AuditEvent;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.dto.AdminRole;

/**
 * An operator's reveal of unmasked MSISDNs (spec 15.4, ticket 36) to {@code mw.audit.event.v1}:
 * action {@code admin.txn.msisdn.revealed}, resource {@code transaction/<txnId>}, actor {@code
 * user}/the operator's {@code preferred_username} (staff id, not masked), {@code after} the role
 * the reveal was granted by and which fields were shown. The revealed values themselves never go
 * into the audit.
 */
public final class RevealAuditMapper {

  public static final String SERVICE = "mw-admin-api";
  public static final String ACTION = "admin.txn.msisdn.revealed";

  /** The party ids a reveal shows whole (only those of MSISDN shape; accounts stay masked). */
  static final List<String> FIELDS = List.of("initiator.id", "debitParty.id", "creditParty.id");

  private RevealAuditMapper() {}

  public static AuditEvent event(Operator operator, AdminRole grantedBy, UUID txnId, String correlationId, Instant at) {
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("role", grantedBy.id());
    after.put("fields", FIELDS);
    return new AuditEvent(
        1,
        UUID.randomUUID().toString(),
        txnId.toString(),
        CorrelationIds.orNew(correlationId),
        new AuditEvent.Actor("user", operator.username()),
        ACTION,
        "transaction/" + txnId,
        null,
        after,
        "SUCCESS",
        at,
        SERVICE);
  }
}
