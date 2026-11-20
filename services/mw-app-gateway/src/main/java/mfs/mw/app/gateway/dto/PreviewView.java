package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Answer of {@code POST /app/v1/{group}/preview} (ticket 105, contract {@code Preview}): what the
 * transaction costs, what is left of the limits, the payee (masked), the id to confirm with, and for
 * a postpaid top-up the outstanding bill and the minimum due (ticket 112b).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PreviewView(
    String previewId,
    Instant expiresAt,
    String txnType,
    Quote.Money amount,
    Quote.Money fee,
    Quote.Money vat,
    Quote.Money commission,
    Quote.Money total,
    Party counterparty,
    Limits limits,
    Quote.Money outstanding,
    Quote.Money minimum) {

  /** A preview with no postpaid bill. */
  @JsonIgnore
  public PreviewView(
      String previewId, Instant expiresAt, String txnType, Quote.Money amount, Quote.Money fee, Quote.Money vat,
      Quote.Money commission, Quote.Money total, Party counterparty, Limits limits) {
    this(previewId, expiresAt, txnType, amount, fee, vat, commission, total, counterparty, limits, null, null);
  }

  /** The payer's limits before this amount. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Limits(Quote.Remaining remaining) {}
}
