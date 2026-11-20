package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Answer of {@code POST /internal/v1/quotes} (ticket 105, 1.5.0 for the postpaid bill): the core's
 * charges (WAL-04), the payer's limit check (WAL-05), the payee, masked (WAL-01 for a wallet
 * payee), and for a postpaid top-up the fetched bill (outstanding and minimum due, ticket 112b).
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
    Party counterparty,
    Amount outstanding,
    Amount minimum) {

  /** A quote with no postpaid bill. */
  @JsonIgnore
  public Quote(
      String txnType, Amount amount, Amount fee, Amount vat, Amount commission, Amount total,
      Limits limits, Party counterparty) {
    this(txnType, amount, fee, vat, commission, total, limits, counterparty, null, null);
  }

  /** The limit check; {@code reasonCode} only when not allowed. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Limits(boolean allowed, String reasonCode, Remaining remaining) {}

  /** What is left before this amount; a limit the payer's rule does not set is null (left out). */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Remaining(Amount perTxn, Amount daily, Amount monthly, Integer dailyCount, Integer monthlyCount) {}
}
