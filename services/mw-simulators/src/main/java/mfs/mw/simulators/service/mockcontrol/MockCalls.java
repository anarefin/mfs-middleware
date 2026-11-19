package mfs.mw.simulators.service.mockcontrol;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.exception.MockAbortException;

/** How a mock party carries out a {@link Decision} (shared by every party). */
public final class MockCalls {

  /** The cut-short body of a MALFORMED_RESPONSE on a call without its own. */
  public static final String CUT_SHORT = "{\"status\":\"";

  private MockCalls() {}

  /**
   * Waits the decision's delay, then: DOWN closes the connection unanswered ({@link
   * MockAbortException}); MAINTENANCE answers {@code maintenance} (nothing applied); else empty, the call
   * goes on.
   */
  public static Optional<SimReply> admit(Decision d, String party, Supplier<SimReply> maintenance) {
    pause(d.delayMs());
    return switch (d.gate()) {
      case DOWN -> throw new MockAbortException(party);
      case MAINTENANCE -> Optional.of(maintenance.get());
      case OPEN -> Optional.empty();
    };
  }

  /**
   * A call whose effect and normal answer is {@code apply} (a status inquiry, a reverse, a biller list):
   * BUSINESS_ERROR answers {@code refusal} with the decision's code (nothing applied); otherwise it is
   * applied, then TIMEOUT holds the answer, SERVER_ERROR_AFTER_SEND answers {@code serverError} with the
   * decision's status (default 500), MALFORMED cuts the body short, CONNECTION_RESET closes the
   * connection; SUCCESS and PARTIAL_SUCCESS answer normally.
   */
  public static SimReply answer(
      Decision d,
      String party,
      Supplier<SimReply> apply,
      Supplier<SimReply> maintenance,
      Supplier<SimReply> serverError,
      Function<String, SimReply> refusal) {
    Optional<SimReply> closed = admit(d, party, maintenance);
    if (closed.isPresent()) {
      return closed.get();
    }
    if (d.mode() == mfs.mw.simulators.dto.SimMode.BUSINESS_FAILURE) {
      return refusal.apply(d.code());
    }
    SimReply normal = apply.get();
    return switch (d.mode()) {
      case TIMEOUT -> {
        pause(d.holdMs());
        yield normal;
      }
      case SERVER_ERROR_AFTER_SEND -> new SimReply(d.statusOr(500), serverError.get().body());
      case MALFORMED -> new SimReply(normal.httpStatus(), CUT_SHORT);
      case CONNECTION_RESET -> throw new MockAbortException(party);
      default -> normal;
    };
  }

  public static void pause(long ms) {
    if (ms <= 0) {
      return;
    }
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
