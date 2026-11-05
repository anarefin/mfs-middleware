package mfs.mw.notify.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** PROMO quiet hours window (spec 10.1). */
class QuietHoursTest {

  static final ZoneId UTC = ZoneId.of("UTC");

  @Test
  void theDefaultIs22To08InDhaka() {
    NotifyProperties.QuietHours q = NotifyProperties.QuietHours.DEFAULT;
    assertThat(q.contains(Instant.parse("2026-11-04T16:00:00Z"))).isTrue(); // 22:00 Dhaka
    assertThat(q.contains(Instant.parse("2026-11-04T15:59:59Z"))).isFalse();
    assertThat(q.contains(Instant.parse("2026-11-05T01:59:59Z"))).isTrue();
    assertThat(q.contains(Instant.parse("2026-11-05T02:00:00Z"))).isFalse(); // 08:00 Dhaka
  }

  @Test
  void aWindowWithinOneDay() {
    var q = new NotifyProperties.QuietHours(true, LocalTime.of(13, 0), LocalTime.of(14, 0), UTC);
    assertThat(q.contains(Instant.parse("2026-11-04T13:30:00Z"))).isTrue();
    assertThat(q.contains(Instant.parse("2026-11-04T14:00:00Z"))).isFalse();
    assertThat(q.contains(Instant.parse("2026-11-04T23:00:00Z"))).isFalse();
  }

  @Test
  void disabledOrEmptyIsNeverQuiet() {
    var off = new NotifyProperties.QuietHours(false, LocalTime.of(22, 0), LocalTime.of(8, 0), UTC);
    var empty = new NotifyProperties.QuietHours(true, LocalTime.of(8, 0), LocalTime.of(8, 0), UTC);
    assertThat(off.contains(Instant.parse("2026-11-04T23:00:00Z"))).isFalse();
    assertThat(empty.contains(Instant.parse("2026-11-04T08:00:00Z"))).isFalse();
  }
}
