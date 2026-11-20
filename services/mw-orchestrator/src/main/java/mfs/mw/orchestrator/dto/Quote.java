package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Answer of {@code POST /internal/v1/quotes} (ticket 105): the core's charges (WAL-04), the payer's
 * limit check (WAL-05) and the payee, masked (WAL-01 for a wallet payee).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Quote(
    String txnType,
    Amount amount,
    Amount fee,
    Amount vat,
    Amount commission,
    Amount total,
    Limits limits,
    Party counterparty) {

  /** The limit check; {@code reasonCode} only when not allowed. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Limits(boolean allowed, String reasonCode, Remaining remaining) {}

  /** What is left before this amount; a limit the payer's rule does not set is null (left out). */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Remaining(Amount perTxn, Amount daily, Amount monthly, Integer dailyCount, Integer monthlyCount) {}
}
