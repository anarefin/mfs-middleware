package mfs.mw.audit.dw.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Filters of the internal audit search (ticket 35); all optional, ANDed.
 *
 * @param from inclusive lower bound of {@code at}
 * @param to exclusive upper bound of {@code at}
 */
public record AuditSearchCriteria(
    UUID txnId,
    String actorType,
    String actorId,
    String action,
    String resource,
    String outcome,
    Instant from,
    Instant to,
    int limit,
    int offset) {}
