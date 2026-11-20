package mfs.mw.portal.api.exception;

/**
 * The core wallet API could not be asked for an org wallet's balance or gave no usable answer
 * (ticket 129c). An approval answers {@code 503 MW-SYS-041}; the dispatcher leaves the batch
 * unstarted until the core answers.
 */
public class CoreUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public CoreUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
