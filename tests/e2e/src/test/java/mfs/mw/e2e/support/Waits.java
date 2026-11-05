package mfs.mw.e2e.support;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/** Polling helpers: the stack is asynchronous (Kafka), so checks wait for a condition with a deadline. */
public final class Waits {

  private Waits() {}

  /** Calls {@code read} until {@code done} holds or {@code timeout} passes; returns the last value. */
  public static <T> T until(Supplier<T> read, java.util.function.Predicate<T> done, Duration timeout) {
    Instant deadline = Instant.now().plus(timeout);
    T v = read.get();
    while (!done.test(v) && Instant.now().isBefore(deadline)) {
      pause(Duration.ofMillis(500));
      v = read.get();
    }
    return v;
  }

  public static void pause(Duration d) {
    try {
      Thread.sleep(d);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
