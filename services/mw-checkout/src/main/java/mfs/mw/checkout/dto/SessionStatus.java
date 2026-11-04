package mfs.mw.checkout.dto;

/**
 * Checkout session states (spec 9.3). Stored: OPEN, PAYING, PAID, FAILED, CANCELLED. EXPIRED is
 * never stored: an OPEN session past its expiry is shown as EXPIRED. PAID, FAILED, CANCELLED and
 * EXPIRED are terminal and send the browser back to the merchant with a signed result.
 */
public enum SessionStatus {
  OPEN,
  PAYING,
  PAID,
  FAILED,
  CANCELLED,
  EXPIRED;

  public boolean terminal() {
    return this == PAID || this == FAILED || this == CANCELLED || this == EXPIRED;
  }
}
