package mfs.mw.simulators.service.mockcontrol;

import mfs.mw.simulators.dto.CallbackMode;
import mfs.mw.simulators.dto.SimMode;

/**
 * What one call of a mock party does (ADR D33), as {@link MockControl#decide} resolved it.
 *
 * @param gate the party switch: OPEN, or DOWN / MAINTENANCE (nothing applied)
 * @param mode the outcome
 * @param code the refusal code replacing the party's default (BUSINESS_ERROR), else null
 * @param httpStatus the status of a SERVER_ERROR_AFTER_SEND or a status-carrying refusal, else null
 * @param holdMs how long a TIMEOUT holds the reply
 * @param delayMs latency before the call is handled (SLOW range plus DELAY)
 * @param callback what happens with the party's callback for this call
 * @param callbackDelayMs delay of a LATE callback
 * @param source {@code scenario:<id>}, {@code magic} or {@code default}
 */
public record Decision(
    Gate gate,
    SimMode mode,
    String code,
    Integer httpStatus,
    long holdMs,
    long delayMs,
    CallbackMode callback,
    long callbackDelayMs,
    String source) {

  public enum Gate {
    OPEN,
    DOWN,
    MAINTENANCE
  }

  /** The magic value's outcome with no scenario, switch or callback (the mock-control profile is off). */
  public static Decision magic(SimMode mode, long holdMs) {
    return new Decision(Gate.OPEN, mode, null, null, holdMs, 0, CallbackMode.NONE, 0,
        mode == SimMode.SUCCESS ? "default" : "magic");
  }

  /** {@code code} when set, else the party's own {@code fallback}. */
  public String codeOr(String fallback) {
    return code != null ? code : fallback;
  }

  /** {@code httpStatus} when set, else {@code fallback}. */
  public int statusOr(int fallback) {
    return httpStatus != null ? httpStatus : fallback;
  }
}
