package mfs.mw.app.gateway.client;

/** mw-notify answered 404 for an inbox id that is not this MSISDN's (ticket 107a). */
public class NotifyNotFoundException extends RuntimeException {

  public NotifyNotFoundException() {
    super("inbox message not found");
  }
}
