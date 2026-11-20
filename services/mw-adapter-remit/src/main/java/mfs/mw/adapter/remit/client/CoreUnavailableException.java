package mfs.mw.adapter.remit.client;

/** The core could not answer (transport, 5xx, 408/429, an unreadable body, no service token). */
public class CoreUnavailableException extends RuntimeException {

  public CoreUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
