package mfs.mw.notify.exception;

/**
 * The push server refused the push for good (ticket 110): a 4xx. Never retried. A 404 (the token is not
 * registered there, FCM {@code UNREGISTERED}) also drops the token from {@code notify.push_device}.
 */
public class PushRefusedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int httpStatus;
  private final String reason;

  public PushRefusedException(int httpStatus, String reason) {
    super("push refused " + httpStatus, null, false, false);
    this.httpStatus = httpStatus;
    this.reason = reason;
  }

  public int httpStatus() {
    return httpStatus;
  }

  /** The push server's error code (UNREGISTERED, INVALID_ARGUMENT, ...), or null. */
  public String reason() {
    return reason;
  }

  /** Whether the token is not (or no longer) registered at the push server. */
  public boolean unregistered() {
    return httpStatus == 404;
  }
}
