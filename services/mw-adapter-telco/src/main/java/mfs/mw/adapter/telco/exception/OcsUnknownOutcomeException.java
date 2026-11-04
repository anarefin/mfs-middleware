package mfs.mw.adapter.telco.exception;

/** The top-up may have reached the OCS and its outcome is not known (spec 7.4). */
public class OcsUnknownOutcomeException extends RuntimeException {

  public enum Reason {
    TIMEOUT,
    CONNECTION_RESET,
    HTTP_5XX,
    MALFORMED_REPLY
  }

  private final Reason reason;

  public OcsUnknownOutcomeException(Reason reason, Throwable cause) {
    super(reason.name(), cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
