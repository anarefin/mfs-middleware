package mfs.mw.adapter.telco.exception;

/** The top-up was certainly not sent to the OCS, so the outcome is a definite failure. */
public class OcsNotSentException extends RuntimeException {

  public enum Reason {
    CONNECT_FAILED,
    CIRCUIT_OPEN,
    BULKHEAD_FULL
  }

  private final Reason reason;

  public OcsNotSentException(Reason reason, Throwable cause) {
    super(reason.name(), cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
