package mfs.mw.notify.exception;

/**
 * A {@code submit_sm} that did not get a message_id (spec 10.4). Transient: throttling (0x58),
 * queue full (0x14), system error (0x08), generic_nack, response timeout, no bound session; the
 * dispatcher retries it. Otherwise the SMSC refused the message for good.
 */
public class SmsSendException extends Exception {

  private static final long serialVersionUID = 1L;

  private final boolean transientError;
  private final int smppStatus;
  private final String bind;

  public SmsSendException(boolean transientError, int smppStatus, String bind, String message) {
    super(message);
    this.transientError = transientError;
    this.smppStatus = smppStatus;
    this.bind = bind;
  }

  public boolean isTransient() {
    return transientError;
  }

  /** SMPP command_status, or -1 when there was no response. */
  public int smppStatus() {
    return smppStatus;
  }

  /** The bind tried, or null when none was available. */
  public String bind() {
    return bind;
  }
}
