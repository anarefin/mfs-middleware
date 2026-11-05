package mfs.mw.ussd.dto;

import java.util.Optional;

/**
 * The transaction states after which nothing changes any more for the channel (spec 7.2), as
 * {@code mw.txn.status.v1} names them. Every other status (or none) is not terminal.
 */
public enum TerminalStatus {
  COMPLETED,
  FAILED,
  REJECTED,
  REVERSED;

  /** The terminal state {@code status} names exactly, or empty for any other status. */
  public static Optional<TerminalStatus> of(String status) {
    if (status == null) {
      return Optional.empty();
    }
    for (TerminalStatus s : values()) {
      if (s.name().equals(status)) {
        return Optional.of(s);
      }
    }
    return Optional.empty();
  }

  /** True only for {@link #COMPLETED}: the transaction went through. */
  public boolean success() {
    return this == COMPLETED;
  }
}
