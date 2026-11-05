package mfs.mw.checkout.dto;

import java.util.Optional;

/**
 * The final states of a transaction on {@code mw.txn.status.v1} (spec 7.2): after them nothing
 * changes any more for the session. The one place the status strings are compared.
 */
public enum TerminalStatus {
  COMPLETED,
  FAILED,
  REJECTED,
  REVERSED;

  /** The terminal status named {@code status}; empty for a running, unknown or missing one. */
  public static Optional<TerminalStatus> of(String status) {
    if (status != null) {
      for (TerminalStatus t : values()) {
        if (t.name().equals(status)) {
          return Optional.of(t);
        }
      }
    }
    return Optional.empty();
  }
}
