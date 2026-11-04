package mfs.mw.notify.exception;

/**
 * An email the SMTP server did not accept (spec 10.4). Transient: no connection, timeouts, 4xx
 * replies; the dispatcher retries it. Permanent: a 5xx reply (unknown or refused recipient, message
 * refused) or a message that cannot be built.
 */
public class EmailSendException extends Exception {

  private static final long serialVersionUID = 1L;

  private final boolean transientError;
  private final int smtpCode;

  public EmailSendException(boolean transientError, int smtpCode, String message) {
    super(message);
    this.transientError = transientError;
    this.smtpCode = smtpCode;
  }

  public boolean isTransient() {
    return transientError;
  }

  /** SMTP reply code, or -1 when there was no reply. */
  public int smtpCode() {
    return smtpCode;
  }
}
