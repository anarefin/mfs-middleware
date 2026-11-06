package mfs.mw.seeder.exception;

/**
 * A call to one of the stack's APIs that did not give the answer the step needs. {@link #status()}
 * is the HTTP status, or 0 when no answer came (connect error, I/O error, request timeout).
 * Messages name the method and route template only: never a body, token or password.
 */
public class RemoteCallException extends RuntimeException {

  private final int status;

  public RemoteCallException(int status, String message) {
    super(message);
    this.status = status;
  }

  protected RemoteCallException(int status, String message, Throwable cause) {
    super(message, cause);
    this.status = status;
  }

  /** No answer at all (connect refused, reset, timed out). */
  public static RemoteCallException unreachable(String call, Throwable cause) {
    return new RemoteCallException(0, call + " got no answer (" + cause.getClass().getSimpleName() + ")", cause);
  }

  public int status() {
    return status;
  }

  /** 503, 504 or no answer: the service is starting or briefly away, so the step is run again. */
  public boolean retryable() {
    return status == 0 || status == 503 || status == 504;
  }
}
