package mfs.mw.checkout.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * When a merchant's IPN is (re)tried (spec 9.3 step 3): the first attempt at once, then after
 * each failed attempt the next delay ({@code mw.checkout.ipn.retry-delays}, 1 min, 5 min, 30 min,
 * 2 h, 6 h, 12 h, 24 h: FRS CH-WEB-04, ticket 116), counted from that attempt. One attempt more than
 * there are delays, then none.
 */
public class IpnRetrySchedule {

  private final List<Duration> delays;
  private final Clock clock;

  public IpnRetrySchedule(List<Duration> delays, Clock clock) {
    this.delays = List.copyOf(delays);
    this.clock = clock;
  }

  public Instant firstAttemptAt() {
    return clock.instant();
  }

  /** When attempt {@code attemptJustMade + 1} is due, measured from now; empty after the last one. */
  public Optional<Instant> retryAfter(int attemptJustMade) {
    if (attemptJustMade < 1 || attemptJustMade > delays.size()) {
      return Optional.empty();
    }
    return Optional.of(clock.instant().plus(delays.get(attemptJustMade - 1)));
  }

  /** How long after the first attempt the last one is made, at the earliest (the sum of the delays). */
  public Duration coverage() {
    return delays.stream().reduce(Duration.ZERO, Duration::plus);
  }

  public int maxAttempts() {
    return delays.size() + 1;
  }
}
