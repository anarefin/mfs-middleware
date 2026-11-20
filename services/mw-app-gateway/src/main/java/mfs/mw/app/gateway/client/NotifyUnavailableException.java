package mfs.mw.app.gateway.client;

/** mw-notify's inbox could not be asked (ticket 107a). */
public class NotifyUnavailableException extends RuntimeException {

  public NotifyUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
