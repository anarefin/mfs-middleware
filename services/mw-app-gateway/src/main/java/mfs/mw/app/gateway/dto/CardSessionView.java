package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The answer of {@code POST /app/v1/add-money/card/sessions} (ticket 118b, contract {@code CardSession}):
 * the acquirer's hosted page once the session is open, or {@code PROCESSING} without a URL on a 202.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CardSessionView(String txnId, String status, String sessionId, String hostedPageUrl, Amount amount) {

  public static CardSessionView processing(String txnId) {
    return new CardSessionView(txnId, TransactionView.PROCESSING, null, null, null);
  }

  /** Whether the session is open (the URL is there). */
  public boolean open() {
    return hostedPageUrl != null;
  }
}
