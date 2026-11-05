package mfs.mw.portal.api.mapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import mfs.mw.portal.api.dto.AuditEvent;
import mfs.mw.portal.api.entity.Batch;
import mfs.mw.portal.api.entity.BatchStatus;
import mfs.mw.portal.api.util.MaskingPolicy;

/**
 * An action on a bulk batch to {@code mw.audit.event.v1} (spec 14.3, M3 gate decision): action
 * {@code bulk.batch.<uploaded|approved|rejected|expired|paused|resumed>}, resource {@code
 * bulk-batch/<batchId>}, actor the operator ({@code user}, the token's {@code preferred_username},
 * staff data and not masked) or {@code system}/{@code mw-portal-api} for the expiry. {@code
 * before}/{@code after} carry the batch status; {@code after} also the org, row count and total,
 * and a rejection's reason scrubbed of customer data (18.3). The rows (MSISDNs) are never copied
 * into an audit event. {@code txnId} is the parent txn once the batch has one.
 */
public final class BatchAuditMapper {

  public static final String SERVICE = "mw-portal-api";
  public static final String PREFIX = "bulk.batch.";

  private BatchAuditMapper() {}

  /**
   * @param action the verb after {@code bulk.batch.}, e.g. {@code approved}
   * @param operator the operator's username, or null for an action of the service itself
   * @param from the status before, or null for a new batch
   * @param to the status after
   * @param reason a rejection's reason, or null
   */
  public static AuditEvent event(
      Batch batch, String action, String operator, BatchStatus from, BatchStatus to, String reason,
      String correlationId, Instant at) {
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("status", to.name());
    after.put("orgId", batch.orgId());
    after.put("rowCount", batch.rowCount());
    after.put("totalMinor", batch.totalMinor());
    after.put("currency", batch.currency());
    if (reason != null) {
      after.put("reason", MaskingPolicy.scrub(reason));
    }
    UUID parent = batch.parentTxnId();
    return new AuditEvent(
        1,
        UUID.randomUUID().toString(),
        parent == null ? null : parent.toString(),
        correlationId == null || correlationId.isBlank() ? "bulk-" + batch.batchId() : correlationId,
        operator == null ? new AuditEvent.Actor("system", SERVICE) : new AuditEvent.Actor("user", operator),
        PREFIX + action,
        "bulk-batch/" + batch.batchId(),
        from == null ? null : Map.of("status", from.name()),
        after,
        "SUCCESS",
        at,
        SERVICE);
  }
}
