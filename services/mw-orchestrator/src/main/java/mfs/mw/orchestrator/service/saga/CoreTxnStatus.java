package mfs.mw.orchestrator.service.saga;

import java.util.Optional;

/**
 * {@code payload.txnStatus} of the core's TransactionStatus answer to a QueryTransaction (contract
 * {@code core.event.v1}, owned by mw-core-mock). Read through {@link #parse}: a value the
 * orchestrator does not know is "no status", which an inquiry treats as a conflict.
 */
public enum CoreTxnStatus {
  NOT_FOUND,
  VALIDATED,
  HELD,
  COMMITTED,
  RELEASED,
  POSTED,
  CREDITED,
  REVERSED,
  REJECTED;

  public static Optional<CoreTxnStatus> parse(String value) {
    if (value == null) {
      return Optional.empty();
    }
    for (CoreTxnStatus s : values()) {
      if (s.name().equals(value)) {
        return Optional.of(s);
      }
    }
    return Optional.empty();
  }
}
