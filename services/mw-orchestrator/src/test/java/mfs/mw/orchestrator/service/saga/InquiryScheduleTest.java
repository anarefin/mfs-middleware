package mfs.mw.orchestrator.service.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The status-inquiry schedule of spec 7.4: 30 s, 1 min, 2 min, 5 min, 15 min, 1 h, then 6 h. */
class InquiryScheduleTest {

  private static final Instant T0 = Instant.parse("2026-11-03T08:00:00Z");

  private final InquirySchedule defaults = InquirySchedule.DEFAULT;

  @Test
  void theDefaultIsTheSpecSchedule() {
    assertThat(defaults.delays())
        .containsExactly(
            Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(2),
            Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofHours(1));
    assertThat(defaults.repeatEvery()).isEqualTo(Duration.ofHours(6));
    assertThat(defaults.cutOff()).isEqualTo(Duration.ofHours(24));
  }

  @Test
  void theFirstInquiryIsThirtySecondsAfterTheOutcomeBecameUnknown() {
    assertThat(defaults.first(T0)).isEqualTo(T0.plusSeconds(30));
  }

  @Test
  void eachLaterInquiryWaitsTheNextSlotThenEverySixHoursUntilTheCutOff() {
    List<Instant> asked = new ArrayList<>();
    Instant now = defaults.first(T0);
    int sent = 0;
    while (!defaults.exhausted(T0, now)) {
      asked.add(now);
      sent++;
      now = defaults.after(sent, T0, now);
    }

    assertThat(asked)
        .startsWith(
            T0.plusSeconds(30),
            T0.plusSeconds(90),
            T0.plusSeconds(210),
            T0.plusSeconds(510),
            T0.plusSeconds(1_410),
            T0.plusSeconds(5_010),
            T0.plusSeconds(5_010 + 6 * 3_600));
    // six slots end at 1 h 23 min 30 s; every 6 h after that fits 3 more before the 24 h cut-off,
    // whose own expiry asks nothing: it hands the transaction to an operator
    assertThat(asked).hasSize(9);
    assertThat(asked.get(asked.size() - 1)).isBefore(T0.plus(Duration.ofHours(24)));
    assertThat(now).isEqualTo(T0.plus(Duration.ofHours(24)));
  }

  @Test
  void noSlotLiesBeyondTheCutOff() {
    InquirySchedule s = new InquirySchedule(List.of(Duration.ofSeconds(10)), Duration.ofHours(6), Duration.ofSeconds(25));
    assertThat(s.after(1, T0, T0.plusSeconds(10))).isEqualTo(T0.plusSeconds(25));
    assertThat(s.exhausted(T0, T0.plusSeconds(24))).isFalse();
    assertThat(s.exhausted(T0, T0.plusSeconds(25))).isTrue();
  }

  @Test
  void aCutOffShorterThanTheFirstSlotStillAsksNothingAfterIt() {
    InquirySchedule s = new InquirySchedule(List.of(Duration.ofMinutes(1)), Duration.ofHours(6), Duration.ofSeconds(5));
    assertThat(s.first(T0)).isEqualTo(T0.plusSeconds(5));
    assertThat(s.exhausted(T0, s.first(T0))).isTrue();
  }

  @Test
  void anEmptyScheduleRepeatsFromTheStart() {
    InquirySchedule s = new InquirySchedule(List.of(), Duration.ofSeconds(3), Duration.ofSeconds(10));
    assertThat(s.first(T0)).isEqualTo(T0.plusSeconds(3));
    assertThat(s.after(1, T0, T0.plusSeconds(3))).isEqualTo(T0.plusSeconds(6));
  }

  @Test
  void zeroOrNegativeDurationsAreRefused() {
    assertThatThrownBy(() -> new InquirySchedule(List.of(Duration.ZERO), Duration.ofHours(6), Duration.ofHours(24)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InquirySchedule(List.of(), Duration.ZERO, Duration.ofHours(24)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InquirySchedule(List.of(), Duration.ofHours(6), Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aPartnerOverrideReplacesOnlyWhatItSets() {
    InquirySchedule merged =
        defaults.overriddenBy(Optional.of(List.of(Duration.ofSeconds(1))), Optional.empty(), Optional.of(Duration.ofSeconds(5)));
    assertThat(merged.delays()).containsExactly(Duration.ofSeconds(1));
    assertThat(merged.repeatEvery()).isEqualTo(Duration.ofHours(6));
    assertThat(merged.cutOff()).isEqualTo(Duration.ofSeconds(5));
  }
}
