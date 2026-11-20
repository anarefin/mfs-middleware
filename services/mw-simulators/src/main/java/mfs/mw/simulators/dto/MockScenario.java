package mfs.mw.simulators.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * One scenario of {@code POST /mock-control/{party}/scenarios} (FRS MCT-01, MCT-02). Scope: {@code key}
 * (an MSISDN, account number, biller code, bill number, transaction id, file name or business date the call
 * carries) and/or {@code nextN} (the next N matching calls or files only); neither = global. {@code operation}
 * null = every operation of the party.
 *
 * @param id set by the engine
 * @param remaining calls left of a {@code nextN} scenario, set by the engine
 * @param count INJECTED_MISMATCH only (ticket 123): how many mismatches of the kind {@code code}, default 1
 */
public record MockScenario(
    String id,
    @Size(max = 64) String operation,
    @NotNull MockBehaviour behaviour,
    @Size(max = 32) String code,
    @Min(0) @Max(600_000) Long delayMs,
    @Min(400) @Max(599) Integer httpStatus,
    @Size(max = 64) String key,
    @Positive Integer nextN,
    Integer remaining,
    @Positive @Max(1000) Integer count) {

  public MockScenario withIdAndRemaining(String newId, Integer left) {
    return new MockScenario(newId, operation, behaviour, code, delayMs, httpStatus, key, nextN, left, count);
  }

  /** A global scenario for {@code operation} (null = all operations). */
  public static MockScenario of(String operation, MockBehaviour behaviour) {
    return new MockScenario(null, operation, behaviour, null, null, null, null, null, null, null);
  }

  public MockScenario withCode(String c) {
    return new MockScenario(id, operation, behaviour, c, delayMs, httpStatus, key, nextN, remaining, count);
  }

  public MockScenario withDelayMs(Long d) {
    return new MockScenario(id, operation, behaviour, code, d, httpStatus, key, nextN, remaining, count);
  }

  public MockScenario withHttpStatus(Integer s) {
    return new MockScenario(id, operation, behaviour, code, delayMs, s, key, nextN, remaining, count);
  }

  public MockScenario withKey(String k) {
    return new MockScenario(id, operation, behaviour, code, delayMs, httpStatus, k, nextN, remaining, count);
  }

  public MockScenario withNextN(Integer n) {
    return new MockScenario(id, operation, behaviour, code, delayMs, httpStatus, key, n, remaining, count);
  }

  public MockScenario withCount(Integer c) {
    return new MockScenario(id, operation, behaviour, code, delayMs, httpStatus, key, nextN, remaining, c);
  }
}
