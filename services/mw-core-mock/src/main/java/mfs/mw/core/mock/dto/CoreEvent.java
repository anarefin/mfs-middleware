package mfs.mw.core.mock.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Result event on {@code core.wallet.event.v1} (spec 6.3; contract {@code core.event.v1}, owned by
 * this service). Failure events carry {@code outcome} FAILURE and a {@code reasonCode}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CoreEvent(
    int schemaVersion,
    String eventType,
    String eventId,
    String commandId,
    String txnId,
    String coreRef,
    Instant occurredAt,
    Outcome outcome,
    String reasonCode,
    String reasonMessage,
    List<BalanceAfter> balancesAfter,
    Map<String, Object> payload) {

  public enum Outcome {
    SUCCESS,
    FAILURE
  }

  /** A wallet balance after the command (customer messages only). */
  public record BalanceAfter(String walletId, Amount balance) {}
}
