package mfs.mw.orchestrator.dto;

import java.time.Instant;
import java.util.UUID;
import mfs.mw.orchestrator.util.Sensitive;
import mfs.mw.orchestrator.util.SensitiveType;

/**
 * Filters of the internal transaction search (ticket 35); every one is optional and they are
 * ANDed. {@code msisdn} and {@code orgId} both match the initiator through its keyed hash.
 * {@code walletId} matches a transaction the wallet pays or is paid by (wallet history, ADR D25),
 * {@code direction} ({@code IN} / {@code OUT}) narrows it to one side and needs {@code walletId}.
 *
 * @param from inclusive lower bound of {@code createdAt}
 * @param to exclusive upper bound of {@code createdAt}
 */
public record TxnSearchCriteria(
    UUID txnId,
    @Sensitive(SensitiveType.MSISDN) String msisdn,
    String orgId,
    @Sensitive(SensitiveType.MSISDN) String walletId,
    String direction,
    String partnerRef,
    String status,
    String txnType,
    String parentTxnId,
    Instant from,
    Instant to,
    int limit,
    int offset) {}
