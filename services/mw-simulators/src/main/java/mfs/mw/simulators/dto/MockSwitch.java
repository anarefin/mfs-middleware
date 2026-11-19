package mfs.mw.simulators.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * A party's switch, {@code PUT /mock-control/{party}/switch} (FRS MCT-03): {@code UP}; {@code DOWN}
 * (the connection is closed unanswered, nothing applied); {@code MAINTENANCE} (the party's maintenance
 * reply, 503 {@code UNAVAILABLE} {@code 5030}, nothing applied); {@code SLOW} (a random latency in
 * {@code [latencyMinMs, latencyMaxMs]}). The latency range applies in every state; {@code ratio} (0..1,
 * default 1) is the share of calls DOWN or MAINTENANCE hits (a flaky party).
 */
public record MockSwitch(
    @NotNull State state,
    @Min(0) @Max(600_000) Long latencyMinMs,
    @Min(0) @Max(600_000) Long latencyMaxMs,
    @DecimalMin("0.0") @DecimalMax("1.0") Double ratio) {

  public static final MockSwitch UP = new MockSwitch(State.UP, 0L, 0L, 1.0);

  public enum State {
    UP,
    DOWN,
    MAINTENANCE,
    SLOW
  }

  public MockSwitch {
    latencyMinMs = latencyMinMs == null ? 0L : latencyMinMs;
    latencyMaxMs = latencyMaxMs == null ? latencyMinMs : Math.max(latencyMaxMs, latencyMinMs);
    ratio = ratio == null ? 1.0 : ratio;
  }
}
