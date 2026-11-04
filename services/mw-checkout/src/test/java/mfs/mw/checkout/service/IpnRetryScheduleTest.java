package mfs.mw.checkout.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import mfs.mw.checkout.MutableClock;
import org.junit.jupiter.api.Test;

/** The IPN retry schedule of spec 9.3: 1 min, 5 min, 30 min, 2 h, 6 h after each failed attempt. */
class IpnRetryScheduleTest {

  static final List<Duration> SPEC =
      List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofHours(6));

  private final MutableClock clock = new MutableClock(Instant.parse("2026-11-05T10:00:00Z"));
  private final IpnRetrySchedule schedule = new IpnRetrySchedule(SPEC, clock);

  @Test
  void theFirstAttemptIsDueAtOnce() {
    assertThat(schedule.firstAttemptAt()).isEqualTo(Instant.parse("2026-11-05T10:00:00Z"));
  }

  @Test
  void eachFailedAttemptIsRetriedAfterTheNextDelayCountedFromThatAttempt() {
    assertThat(schedule.retryAfter(1)).contains(Instant.parse("2026-11-05T10:01:00Z"));
    clock.advance(Duration.ofMinutes(1).plusSeconds(7)); // the job ran a little late
    assertThat(schedule.retryAfter(2)).contains(Instant.parse("2026-11-05T10:06:07Z"));
    clock.advance(Duration.ofMinutes(5));
    assertThat(schedule.retryAfter(3)).contains(Instant.parse("2026-11-05T10:36:07Z"));
    clock.advance(Duration.ofMinutes(30));
    assertThat(schedule.retryAfter(4)).contains(Instant.parse("2026-11-05T12:36:07Z"));
    clock.advance(Duration.ofHours(2));
    assertThat(schedule.retryAfter(5)).contains(Instant.parse("2026-11-05T18:36:07Z"));
  }

  @Test
  void afterTheSixthAttemptThereIsNoOther() {
    assertThat(schedule.maxAttempts()).isEqualTo(6);
    assertThat(schedule.retryAfter(6)).isEmpty();
    assertThat(schedule.retryAfter(7)).isEmpty();
  }
}
