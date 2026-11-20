package mfs.mw.orchestrator.service.saga;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * When to ask about an UNKNOWN outcome (FRS PLT-023/024, spec 7.4, ticket 96a): one inquiry after
 * each of the {@code delays} in turn (default 30 s, 2 min, 10 min, 1 h); once the last inquiry has
 * had {@code answerWait} to be answered, the slot asks nothing and the transaction goes to
 * MANUAL_REVIEW. Plain Java, no clock: callers pass the times.
 *
 * @param delays the wait before the first, second, ... inquiry (at least one)
 * @param answerWait how long the answer to the last inquiry is awaited before MANUAL_REVIEW
 */
public record InquirySchedule(List<Duration> delays, Duration answerWait) {

  public static final Duration DEFAULT_ANSWER_WAIT = Duration.ofSeconds(30);

  public static final InquirySchedule DEFAULT =
      new InquirySchedule(
          List.of(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofHours(1)),
          DEFAULT_ANSWER_WAIT);

  public InquirySchedule {
    delays = List.copyOf(delays);
    if (delays.isEmpty()) {
      throw new IllegalArgumentException("the inquiry schedule needs at least one wait");
    }
    for (Duration d : delays) {
      requirePositive(d, "delay");
    }
    requirePositive(answerWait, "answerWait");
  }

  /** When the first inquiry is due for an outcome unknown since {@code unknownSince}. */
  public Instant first(Instant unknownSince) {
    return unknownSince.plus(delays.get(0));
  }

  /**
   * The next slot, {@code sent} inquiries having gone out, the last at {@code now}: the next
   * inquiry, or once every wait is used the hand-over to MANUAL_REVIEW after {@code answerWait}.
   */
  public Instant next(int sent, Instant now) {
    return now.plus(sent < delays.size() ? delays.get(sent) : answerWait);
  }

  /** True once every inquiry of the schedule was sent: the slot due now asks nothing. */
  public boolean exhausted(int sent) {
    return sent >= delays.size();
  }

  /** This schedule with a partner's own list of waits (spec 11, per-partner TSQ schedule). */
  public InquirySchedule withDelays(List<Duration> partnerDelays) {
    return new InquirySchedule(partnerDelays, answerWait);
  }

  private static void requirePositive(Duration d, String name) {
    if (d == null || d.isZero() || d.isNegative()) {
      throw new IllegalArgumentException(name + " must be a positive duration, was " + d);
    }
  }
}
