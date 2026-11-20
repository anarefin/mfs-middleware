package mfs.mw.ussd.exception;

/** The core account read could not be completed (connect failure, timeout, 5xx, unreadable answer). */
public class CoreUnavailableException extends RuntimeException {

  public CoreUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
