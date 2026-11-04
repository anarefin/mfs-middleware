package mfs.mw.ussd.exception;

/**
 * mw-adapter-billpay could not be asked (connect failure, timeout, 5xx without an envelope,
 * unreadable answer); counted by the circuit breaker.
 */
public class BillPayUnavailableException extends RuntimeException {

  public BillPayUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
