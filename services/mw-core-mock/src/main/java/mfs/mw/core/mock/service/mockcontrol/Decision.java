package mfs.mw.core.mock.service.mockcontrol;

import mfs.mw.core.mock.dto.MockMode;
import mfs.mw.core.mock.dto.ReplyMode;

/**
 * What one call of the core party does (ADR D33, ticket 103), as {@link MockControl#decide} resolved it.
 *
 * @param gate the party switch: OPEN, or DOWN / MAINTENANCE (nothing applied)
 * @param mode the outcome
 * @param code the refusal reason of a BUSINESS_FAILURE (default {@link #DEFAULT_REASON})
 * @param httpStatus the status of a SERVER_ERROR_AFTER_SEND (REST), else null
 * @param holdMs how long a TIMEOUT holds the answer
 * @param delayMs latency before the answer (SLOW range plus DELAY)
 * @param reply what happens with the command's result event
 * @param replyDelayMs extra delay of a LATE result event
 * @param source {@code scenario:<id>}, {@code switch} or {@code default}
 */
public record Decision(
    Gate gate,
    MockMode mode,
    String code,
    Integer httpStatus,
    long holdMs,
    long delayMs,
    ReplyMode reply,
    long replyDelayMs,
    String source) {

  /** The refusal reason of a BUSINESS_ERROR scenario without a {@code code} (as the old REJECT fault). */
  public static final String DEFAULT_REASON = "INJECTED_REJECT";

  /** The refusal reason the core answers a command with while it is in MAINTENANCE. */
  public static final String MAINTENANCE_REASON = "CORE_UNAVAILABLE";

  /** No scenario, no switch: the ledger rules decide, the result event goes once at once. */
  public static final Decision PASS =
      new Decision(Gate.OPEN, MockMode.SUCCESS, null, null, 0, 0, ReplyMode.ONCE, 0, "default");

  public enum Gate {
    OPEN,
    DOWN,
    MAINTENANCE
  }

  /** A business refusal decided by a scenario. */
  public boolean refuses() {
    return gate == Gate.OPEN && mode == MockMode.BUSINESS_FAILURE;
  }

  /** The reason of a refusal. */
  public String reason() {
    return code != null && !code.isBlank() ? code : DEFAULT_REASON;
  }

  /** {@code httpStatus} when set, else {@code fallback}. */
  public int statusOr(int fallback) {
    return httpStatus != null ? httpStatus : fallback;
  }

  /**
   * The result event is never published: NO_CALLBACK, or an outcome where the caller never sees an
   * answer (HTTP_5XX, MALFORMED_RESPONSE, CONNECTION_RESET: applied, reply lost).
   */
  public boolean replyLost() {
    return reply == ReplyMode.NONE
        || mode == MockMode.SERVER_ERROR_AFTER_SEND
        || mode == MockMode.MALFORMED
        || mode == MockMode.CONNECTION_RESET;
  }

  /** How long the result event waits in the outbox: delay, a TIMEOUT's hold, a LATE reply's delay. */
  public long replyAfterMs() {
    return delayMs + (mode == MockMode.TIMEOUT ? holdMs : 0) + (reply == ReplyMode.LATE ? replyDelayMs : 0);
  }
}
