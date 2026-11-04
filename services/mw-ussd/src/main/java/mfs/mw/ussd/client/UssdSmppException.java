package mfs.mw.ussd.client;

/** A USSD {@code submit_sm} that did not reach the MNO after its retries (spec 9.2.1). */
public class UssdSmppException extends Exception {

  private static final long serialVersionUID = 1L;

  private final int commandStatus;

  public UssdSmppException(String message, int commandStatus) {
    super(message);
    this.commandStatus = commandStatus;
  }

  /** The last command_status the MNO answered, or -1 (no answer, no bind). */
  public int commandStatus() {
    return commandStatus;
  }
}
