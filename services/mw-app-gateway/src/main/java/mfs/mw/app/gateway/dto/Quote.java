package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * mw-orchestrator's quote ({@code POST /internal/v1/quotes}, contract {@code
 * api/mw-orchestrator/txn-query.v1.openapi.yaml} 1.4.0 {@code Quote}, ticket 105): the core's charges,
 * the payer's limit check and the payee, already masked there. Unknown fields are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Quote(
    String txnType,
    Money amount,
    Money fee,
    Money vat,
    Money commission,
    Money total,
    Limits limits,
    Party counterparty) {

  /** An amount (poisha, BDT). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Money(long valueMinor, String currency) {}

  /** The limit check; {@code reasonCode} only when not allowed. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Limits(boolean allowed, String reasonCode, Remaining remaining) {}

  /** What is left before this amount; a limit not set is null. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Remaining(Money perTxn, Money daily, Money monthly, Integer dailyCount, Integer monthlyCount) {}
}
