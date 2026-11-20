package mfs.mw.notify.exception;

/**
 * A push that did not go through for a reason that may pass (ticket 110): a 5xx, a timeout, an I/O error,
 * the circuit breaker open, the push client switched off. {@code client/PushClient} retries it with
 * backoff; then the dispatcher schedules the next attempt (2 s, 10 s, 30 s) and finally fails the message.
 */
public class PushSendException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int httpStatus;

  public PushSendException(int httpStatus, String message) {
    super(message, null, false, false);
    this.httpStatus = httpStatus;
  }

  /** The HTTP status, or -1 when there was no answer. */
  public int httpStatus() {
    return httpStatus;
  }
}
