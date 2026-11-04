package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * What the app sees of a transaction ({@code POST} 200/202 and {@code GET
 * /app/v1/transactions/{txnId}}). {@code status} is a saga state of {@code mw.txn.status.v1},
 * or {@code PROCESSING} on a 202 when the answer did not arrive within the budget (spec 7.5).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionView(
    String txnId,
    String status,
    String txnType,
    Party debitParty,
    Party creditParty,
    Amount amount,
    Amount fee,
    String coreRef,
    ErrorInfo error,
    Instant requestedAt,
    Instant updatedAt) {

  public static final String PROCESSING = "PROCESSING";

  public static TransactionView processing(String txnId) {
    return new TransactionView(txnId, PROCESSING, null, null, null, null, null, null, null, null, null);
  }

  public static TransactionView of(StatusMessage s) {
    return new TransactionView(
        s.txnId(), s.status(), s.txnType(), s.debitParty(), s.creditParty(), s.amount(), s.fee(),
        s.coreRef(), s.error(), s.requestedAt(), s.updatedAt());
  }

  public TransactionView withStatus(String newStatus) {
    return new TransactionView(
        txnId, newStatus, txnType, debitParty, creditParty, amount, fee, coreRef, error,
        requestedAt, updatedAt);
  }
}
