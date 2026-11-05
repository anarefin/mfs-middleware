package mfs.mw.orchestrator.dto;

import java.time.Instant;
import java.util.UUID;
import mfs.mw.orchestrator.util.Sensitive;
import mfs.mw.orchestrator.util.SensitiveType;

/**
 * Filters of the internal transaction search (ticket 35); every one is optional and they are
 * ANDed. {@code msisdn} and {@code orgId} both match the initiator through its keyed hash.
 *
 * @param from inclusive lower bound of {@code createdAt}
 * @param to exclusive upper bound of {@code createdAt}
 */
public record TxnSearchCriteria(
    UUID txnId,
    @Sensitive(SensitiveType.MSISDN) String msisdn,
    String orgId,
    String partnerRef,
    String status,
    String txnType,
    String parentTxnId,
    Instant from,
    Instant to,
    int limit,
    int offset) {}
