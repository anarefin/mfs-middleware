package mfs.mw.simulators.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;

/**
 * Per-partner behaviour set through {@code POST /sim/{partner}/scenario} (spec 22.2).
 *
 * @param forceMode when set, every request gets this outcome instead of the magic values
 * @param latencyMs delay before every reply
 * @param errorRate share of requests (0..1) answered 503 without being applied
 * @param outage every request is answered 503 without being applied
 * @param timeoutDelayMs hold time of the TIMEOUT outcome; null uses {@code sim.timeout-delay}
 * @param callbackMode callback behaviour after a request is applied
 * @param callbackUrl where callbacks are posted
 * @param callbackDelayMs delay of a LATE callback
 * @param callbackOnly payments are accepted with 202 PENDING and their outcome comes only by
 *     callback (billagg, ticket 22); the status inquiry still knows the real outcome
 */
public record Scenario(
    SimMode forceMode,
    @Min(0) long latencyMs,
    @DecimalMin("0.0") @DecimalMax("1.0") double errorRate,
    boolean outage,
    @Min(0) Long timeoutDelayMs,
    CallbackMode callbackMode,
    String callbackUrl,
    @Min(0) long callbackDelayMs,
    boolean callbackOnly) {

  public static final Scenario DEFAULT =
      new Scenario(null, 0, 0.0, false, null, CallbackMode.NONE, null, 0, false);

  public Scenario {
    if (callbackMode == null) {
      callbackMode = CallbackMode.NONE;
    }
  }

  public Scenario withForceMode(SimMode m) {
    return new Scenario(m, latencyMs, errorRate, outage, timeoutDelayMs, callbackMode, callbackUrl, callbackDelayMs, callbackOnly);
  }

  public Scenario withLatencyMs(long v) {
    return new Scenario(forceMode, v, errorRate, outage, timeoutDelayMs, callbackMode, callbackUrl, callbackDelayMs, callbackOnly);
  }

  public Scenario withErrorRate(double v) {
    return new Scenario(forceMode, latencyMs, v, outage, timeoutDelayMs, callbackMode, callbackUrl, callbackDelayMs, callbackOnly);
  }

  public Scenario withOutage(boolean v) {
    return new Scenario(forceMode, latencyMs, errorRate, v, timeoutDelayMs, callbackMode, callbackUrl, callbackDelayMs, callbackOnly);
  }

  public Scenario withTimeoutDelayMs(Long v) {
    return new Scenario(forceMode, latencyMs, errorRate, outage, v, callbackMode, callbackUrl, callbackDelayMs, callbackOnly);
  }

  public Scenario withCallbackOnly(boolean v) {
    return new Scenario(forceMode, latencyMs, errorRate, outage, timeoutDelayMs, callbackMode, callbackUrl, callbackDelayMs, v);
  }

  public Scenario withCallback(CallbackMode mode, String url, long delayMs) {
    return new Scenario(forceMode, latencyMs, errorRate, outage, timeoutDelayMs, mode, url, delayMs, callbackOnly);
  }
}
