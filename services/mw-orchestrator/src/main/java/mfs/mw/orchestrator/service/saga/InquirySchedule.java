package mfs.mw.orchestrator.service.saga;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * When to ask about an UNKNOWN outcome (spec 7.4): the {@code delays} one after another (default
 * 30 s, 1 min, 2 min, 5 min, 15 min, 1 h), then every {@code repeatEvery} (6 h), until {@code
 * cutOff} after the outcome became unknown (the partner's settlement cut-off; 24 h by default). The
 * expiry at the cut-off asks nothing: the transaction goes to MANUAL_REVIEW. Plain Java, no clock:
 * callers pass the times.
 *
 * @param delays the wait before the first, second, ... inquiry
 * @param repeatEvery the wait between inquiries once {@code delays} are used up
 * @param cutOff how long after entering UNKNOWN inquiries stop
 */
public record InquirySchedule(List<Duration> delays, Duration repeatEvery, Duration cutOff) {

  public static final InquirySchedule DEFAULT =
      new InquirySchedule(
          List.of(
              Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(2),
              Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofHours(1)),
          Duration.ofHours(6),
          Duration.ofHours(24));

  public InquirySchedule {
    delays = List.copyOf(delays);
    for (Duration d : delays) {
      requirePositive(d, "delay");
    }
    requirePositive(repeatEvery, "repeatEvery");
    requirePositive(cutOff, "cutOff");
  }

  /** When the first inquiry is due for an outcome unknown since {@code unknownSince}. */
  public Instant first(Instant unknownSince) {
    return capped(unknownSince.plus(delay(0)), unknownSince);
  }

  /** When the next inquiry is due, {@code sent} inquiries having been sent, the last at {@code now}. */
  public Instant after(int sent, Instant unknownSince, Instant now) {
    return capped(now.plus(delay(sent)), unknownSince);
  }

  /** True once the cut-off has passed: no more inquiries, an operator decides. */
  public boolean exhausted(Instant unknownSince, Instant now) {
    return !now.isBefore(unknownSince.plus(cutOff));
  }

  /** This schedule with the parts a partner sets replaced (spec 11, per-partner TSQ schedule). */
  public InquirySchedule overriddenBy(
      Optional<List<Duration>> partnerDelays, Optional<Duration> partnerRepeat, Optional<Duration> partnerCutOff) {
    return new InquirySchedule(
        partnerDelays.orElse(delays), partnerRepeat.orElse(repeatEvery), partnerCutOff.orElse(cutOff));
  }

  private Duration delay(int index) {
    return index < delays.size() ? delays.get(index) : repeatEvery;
  }

  private Instant capped(Instant due, Instant unknownSince) {
    Instant cut = unknownSince.plus(cutOff);
    return due.isAfter(cut) ? cut : due;
  }

  private static void requirePositive(Duration d, String name) {
    if (d == null || d.isZero() || d.isNegative()) {
      throw new IllegalArgumentException(name + " must be a positive duration, was " + d);
    }
  }
}
