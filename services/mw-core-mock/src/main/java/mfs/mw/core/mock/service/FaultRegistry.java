package mfs.mw.core.mock.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import mfs.mw.core.mock.dto.Fault;
import mfs.mw.core.mock.dto.Fault.FaultMode;
import mfs.mw.core.mock.dto.FaultRequest;
import mfs.mw.core.mock.exception.ErrorCodes;
import mfs.mw.core.mock.exception.MwException;
import org.springframework.stereotype.Service;

/**
 * Faults injected through the admin API, kept in memory (a test double: a restart clears them, a
 * reset call clears them too). Thread-safe; every method takes the one lock.
 */
@Service
public class FaultRegistry {

  static final Set<String> COMMAND_TYPES =
      Set.of(
          "ValidateTransaction", "PostTransfer", "HoldFunds", "CommitHold", "ReleaseHold",
          "CreditWallet", "ReverseTransaction", "QueryTransaction");

  private final List<Fault> faults = new ArrayList<>();

  /** Adds a fault; refuses a request that could never fire or is missing its parameter. */
  public synchronized Fault add(FaultRequest r) {
    if (r.mode() == FaultMode.DELAY && r.delayMs() == null) {
      throw new MwException(ErrorCodes.VAL_INVALID, "DELAY needs delayMs");
    }
    if (!blank(r.commandType()) && !COMMAND_TYPES.contains(r.commandType())) {
      throw new MwException(ErrorCodes.VAL_INVALID, "unknown commandType");
    }
    String reason = r.mode() == FaultMode.REJECT ? (blank(r.reasonCode()) ? "INJECTED_REJECT" : r.reasonCode()) : null;
    Long delay = r.mode() == FaultMode.DELAY ? r.delayMs() : null;
    Fault f = new Fault(UUID.randomUUID().toString(), r.mode(), blankToNull(r.commandType()), blankToNull(r.txnId()), delay, reason, r.times());
    faults.add(f);
    return f;
  }

  public synchronized List<Fault> list() {
    return List.copyOf(faults);
  }

  /** Clears every fault. */
  public synchronized void reset() {
    faults.clear();
  }

  /** Clears one fault; false when it does not exist (any more). */
  public synchronized boolean remove(String id) {
    return faults.removeIf(f -> f.id().equals(id));
  }

  /** The faults that apply to this command, each counted as used once. */
  synchronized List<Fault> consume(String commandType, String txnId) {
    List<Fault> hit = new ArrayList<>();
    for (int i = 0; i < faults.size(); i++) {
      Fault f = faults.get(i);
      if (!f.matches(commandType, txnId)) {
        continue;
      }
      hit.add(f);
      if (f.remaining() != null) {
        if (f.remaining() <= 1) {
          faults.remove(i--);
        } else {
          faults.set(
              i,
              new Fault(f.id(), f.mode(), f.commandType(), f.txnId(), f.delayMs(), f.reasonCode(), f.remaining() - 1));
        }
      }
    }
    return hit;
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static String blankToNull(String s) {
    return blank(s) ? null : s;
  }
}
