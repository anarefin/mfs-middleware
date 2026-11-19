package mfs.mw.simulators.exception;

/**
 * Thrown by a mock party when mock control says the connection goes without an answer (switch DOWN,
 * scenario CONNECTION_RESET). {@link GlobalExceptionHandler} marks the request and {@code
 * filter/MockAbortValve} closes the socket before anything is written.
 */
public class MockAbortException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String party;

  public MockAbortException(String party) {
    super("mock party " + party + " closes the connection", null, false, false);
    this.party = party;
  }

  public String party() {
    return party;
  }
}
