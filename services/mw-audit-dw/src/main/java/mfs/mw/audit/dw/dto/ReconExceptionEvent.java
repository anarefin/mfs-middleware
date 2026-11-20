package mfs.mw.audit.dw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;
import mfs.mw.audit.dw.util.Sensitive;
import mfs.mw.audit.dw.util.SensitiveType;

/**
 * {@code mw.recon.exception.v1} (owner mw-recon, ticket 127b): one state change of a reconciliation exception
 * (OPEN, EXPLAINED, ACTIONED, RESOLVED). Stored in the audit trail by {@code service/ReconExceptionAudit}. Unknown
 * fields are ignored (6.1).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReconExceptionEvent(
    int schemaVersion,
    UUID eventId,
    UUID exceptionId,
    String state,
    String exceptionClass,
    String detail,
    String owner,
    String partnerId,
    UUID txnId,
    Long amountMinor,
    String statementRef,
    String action,
    UUID changeRequestId,
    @Sensitive(SensitiveType.NAME) String actor,
    Instant detectedAt,
    Instant changedAt,
    String correlationId) {}
