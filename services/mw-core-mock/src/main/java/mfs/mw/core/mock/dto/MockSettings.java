package mfs.mw.core.mock.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * A party's settings, {@code PUT /mock-control/{party}/settings}: where its callbacks go ({@code
 * callbackUrl}; none = no callbacks; one per applied call, the callback scenarios change how), {@code
 * callbackOnly} (billagg: payments answered 202 PENDING, outcome by callback only) and {@code
 * timeoutDelayMs} (hold of a TIMEOUT outcome; null = {@code mw.coremock.mock-control.timeout-delay}).
 * Copied from mw-simulators (ticket 103); the core party uses {@code timeoutDelayMs} only.
 */
public record MockSettings(
    @Size(max = 2048) String callbackUrl, Boolean callbackOnly, @Min(0) @Max(600_000) Long timeoutDelayMs) {

  public static final MockSettings DEFAULT = new MockSettings(null, false, null);

  public MockSettings {
    callbackOnly = Boolean.TRUE.equals(callbackOnly);
  }
}
