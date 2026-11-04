package mfs.mw.simulators.exception;

/** The SMSC simulator refuses a {@code submit_sm} with this SMPP command_status. */
public class SmscRefusalException extends Exception {

  private static final long serialVersionUID = 1L;

  private final int commandStatus;

  public SmscRefusalException(int commandStatus, String message) {
    super(message);
    this.commandStatus = commandStatus;
  }

  public int commandStatus() {
    return commandStatus;
  }
}
