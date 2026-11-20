package mfs.mw.adapter.remit.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The beneficiary lookup's quota per authenticated partner (ticket 119c): at most {@code
 * mw.remit.lookup.max-per-window} lookups per fixed {@code mw.remit.lookup.window} (60 a minute), so
 * a partner cannot walk the number range for names. Per instance, on top of the edge's per-address
 * quota (APISIX {@code partner-remit-v1}). Counted only after the signature verified, so a forger
 * burns no partner's quota.
 */
@Component
public class LookupRateLimiter {

  private record Window(Instant start, int count) {}

  private final Clock clock;
  private final int max;
  private final Duration window;
  private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

  public LookupRateLimiter(
      Clock clock,
      @Value("${mw.remit.lookup.max-per-window:60}") int max,
      @Value("${mw.remit.lookup.window:1m}") Duration window) {
    if (max < 1 || window.isNegative() || window.isZero()) {
      throw new IllegalArgumentException("mw.remit.lookup: max-per-window >= 1 and a positive window");
    }
    this.clock = clock;
    this.max = max;
    this.window = window;
  }

  /** Counts one lookup of {@code partnerId}; false when its quota of this window is used up. */
  public boolean tryAcquire(String partnerId) {
    Instant now = clock.instant();
    Window w =
        windows.compute(
            partnerId,
            (k, cur) -> cur == null || !now.isBefore(cur.start().plus(window))
                ? new Window(now, 1)
                : new Window(cur.start(), Math.min(cur.count() + 1, max + 1)));
    return w.count() <= max;
  }
}
