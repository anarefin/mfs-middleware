package mfs.mw.portal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One transaction of the SP's own org as the portal shows it (ticket 35). Read from
 * mw-orchestrator's {@code txn-query.v1}, which masks customer data (spec 18.3); fields the SP has
 * no use for (correlation id, channel, initiator, partner calls) are dropped on reading.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrgTxn(
    String txnId,
    String txnType,
    String status,
    Instant createdAt,
    Instant updatedAt,
    Amount amount,
    Amount fee,
    Party debitParty,
    Party creditParty,
    String reversalTxnId,
    Error error,
    Map<String, String> attributes) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Error(String code, String message, String messageBn, Boolean retryable) {}

  /** One page, newest first. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Page(List<OrgTxn> items, int limit, int offset) {}

  /** A transaction and its state changes in order. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Detail(OrgTxn transaction, List<Step> timeline) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Step(String from, String to, Instant at) {}
}
