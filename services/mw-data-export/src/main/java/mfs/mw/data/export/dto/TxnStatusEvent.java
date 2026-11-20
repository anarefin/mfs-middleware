package mfs.mw.data.export.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/** The fields of {@code mw.txn.status.v1} this service projects. Unknown fields are ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TxnStatusEvent(
    String txnId,
    String correlationId,
    String channel,
    String txnType,
    String status,
    String previousStatus,
    Party debitParty,
    Party creditParty,
    Amount amount,
    Instant requestedAt,
    Instant updatedAt,
    Instant completedAt) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Party(String type, String id) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Amount(long valueMinor, String currency) {}
}
