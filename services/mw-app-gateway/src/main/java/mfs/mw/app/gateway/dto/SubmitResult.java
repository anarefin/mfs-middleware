package mfs.mw.app.gateway.dto;

/**
 * Outcome of a submitted transaction: {@code done} when a terminal status arrived within the
 * budget (HTTP 200), otherwise still running (HTTP 202 with the txnId).
 */
public record SubmitResult(boolean done, TransactionView view) {

  public static SubmitResult done(TransactionView view) {
    return new SubmitResult(true, view);
  }

  public static SubmitResult processing(TransactionView view) {
    return new SubmitResult(false, view);
  }
}
