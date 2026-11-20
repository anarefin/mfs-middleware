package mfs.mw.audit.dw.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Filters of the internal audit search (ticket 35, ticket 130); all optional, ANDed. The
 * transaction filters ({@code partner}, {@code channel}, {@code status}, {@code amountMin}, {@code
 * amountMax}, {@code msisdnToken}) match the events of the transactions whose search projection
 * ({@code audit.txn_index}) matches.
 *
 * @param from inclusive lower bound of {@code at}
 * @param to exclusive upper bound of {@code at}
 * @param status the transaction's latest status
 * @param amountMin inclusive lower bound of the amount, minor units
 * @param amountMax inclusive upper bound of the amount, minor units
 * @param msisdnToken the TOKENISE_MSISDN token of a party's MSISDN (the service tokenises the
 *     operator's input; the MSISDN itself is never a filter value here)
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
    int offset,
    String partner,
    String channel,
    String status,
    Long amountMin,
    Long amountMax,
    String msisdnToken) {

  /** The ticket 35 filters only. */
  public AuditSearchCriteria(
      UUID txnId, String actorType, String actorId, String action, String resource, String outcome, Instant from,
      Instant to, int limit, int offset) {
    this(txnId, actorType, actorId, action, resource, outcome, from, to, limit, offset, null, null, null, null, null,
        null);
  }

  /** Whether a filter needs the transaction projection. */
  public boolean filtersTransactions() {
    return partner != null || channel != null || status != null || amountMin != null || amountMax != null
        || msisdnToken != null;
  }

  public AuditSearchCriteria withMsisdnToken(String token) {
    return new AuditSearchCriteria(txnId, actorType, actorId, action, resource, outcome, from, to, limit, offset,
        partner, channel, status, amountMin, amountMax, token);
  }

  public AuditSearchCriteria withPage(int newLimit, int newOffset) {
    return new AuditSearchCriteria(txnId, actorType, actorId, action, resource, outcome, from, to, newLimit,
        newOffset, partner, channel, status, amountMin, amountMax, msisdnToken);
  }
}
