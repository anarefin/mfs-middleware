package mfs.mw.orchestrator.service.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The status-inquiry schedule of FRS PLT-023/024 (ticket 96a): inquiries after 30 s, 2 min, 10 min
 * and 1 h, then one {@code answerWait} for the last answer, then MANUAL_REVIEW.
 */
class InquiryScheduleTest {

  private static final Instant T0 = Instant.parse("2026-11-03T08:00:00Z");

  private final InquirySchedule defaults = InquirySchedule.DEFAULT;

  @Test
  void theDefaultIsTheFrsSchedule() {
    assertThat(defaults.delays())
        .containsExactly(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofHours(1));
    assertThat(defaults.answerWait()).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void theFirstInquiryIsThirtySecondsAfterTheOutcomeBecameUnknown() {
    assertThat(defaults.first(T0)).isEqualTo(T0.plusSeconds(30));
  }

  @Test
  void fourInquiriesThenManualReviewOnceTheLastAnswerHadItsTime() {
    List<Instant> asked = new ArrayList<>();
    Instant now = defaults.first(T0);
    int sent = 0;
    while (!defaults.exhausted(sent)) {
      asked.add(now);
      sent++;
      now = defaults.next(sent, now);
    }

    assertThat(asked)
        .containsExactly(
            T0.plusSeconds(30),
            T0.plusSeconds(30 + 120),
            T0.plusSeconds(30 + 120 + 600),
            T0.plusSeconds(30 + 120 + 600 + 3_600));
    // the slot after the fourth inquiry asks nothing: it hands the transaction to an operator
    assertThat(sent).isEqualTo(4);
    assertThat(now).isEqualTo(T0.plusSeconds(30 + 120 + 600 + 3_600 + 30));
  }

  @Test
  void theScheduleIsExhaustedOnlyOnceEveryWaitWasUsed() {
    assertThat(defaults.exhausted(0)).isFalse();
    assertThat(defaults.exhausted(3)).isFalse();
    assertThat(defaults.exhausted(4)).isTrue();
  }

  @Test
  void aCustomListGivesItsOwnInquiries() {
    InquirySchedule s = new InquirySchedule(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), Duration.ofSeconds(5));
    assertThat(s.first(T0)).isEqualTo(T0.plusSeconds(1));
    assertThat(s.next(1, T0.plusSeconds(1))).isEqualTo(T0.plusSeconds(3));
    assertThat(s.next(2, T0.plusSeconds(3))).isEqualTo(T0.plusSeconds(8));
    assertThat(s.exhausted(2)).isTrue();
  }

  @Test
  void anEmptyListOrNonPositiveDurationIsRefused() {
    assertThatThrownBy(() -> new InquirySchedule(List.of(), Duration.ofSeconds(30)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InquirySchedule(List.of(Duration.ZERO), Duration.ofSeconds(30)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InquirySchedule(List.of(Duration.ofSeconds(1)), Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aPartnerOverrideReplacesOnlyTheListOfWaits() {
    InquirySchedule merged = defaults.withDelays(List.of(Duration.ofSeconds(1)));
    assertThat(merged.delays()).containsExactly(Duration.ofSeconds(1));
    assertThat(merged.answerWait()).isEqualTo(defaults.answerWait());
  }
}
