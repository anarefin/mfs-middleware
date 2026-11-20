package mfs.mw.checkout.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import mfs.mw.checkout.MutableClock;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.io.ClassPathResource;

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

  /** FRS CH-WEB-04 (ticket 116): the default schedule keeps trying a merchant for at least 24 h. */
  @Test
  void theDefaultScheduleCoversADay() {
    YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new ClassPathResource("application.yml"));
    String value = yaml.getObject().getProperty("mw.checkout.ipn.retry-delays");
    String defaults = value.substring(value.indexOf(':') + 1, value.lastIndexOf('}'));
    List<Duration> delays = Arrays.stream(defaults.split(",")).map(d -> DurationStyle.detectAndParse(d.trim())).toList();

    assertThat(delays).containsExactly(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
        Duration.ofHours(2), Duration.ofHours(6), Duration.ofHours(12), Duration.ofHours(24));
    assertThat(new IpnRetrySchedule(delays, clock).coverage()).isGreaterThanOrEqualTo(Duration.ofHours(24));
  }
}
