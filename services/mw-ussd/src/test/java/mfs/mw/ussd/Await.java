package mfs.mw.ussd;

import java.time.Duration;
import java.util.concurrent.Callable;

/** Polls a condition until it holds or the time is up (the catalog carries no Awaitility). */
public final class Await {

  private final Duration timeout;

  private Await(Duration timeout) {
    this.timeout = timeout;
  }

  public static Await await() {
    return new Await(Duration.ofSeconds(10));
  }

  public Await atMost(Duration atMost) {
    return new Await(atMost);
  }

  public void until(Callable<Boolean> condition) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      try {
        if (Boolean.TRUE.equals(condition.call())) {
          return;
        }
      } catch (Exception e) {
        // not yet
      }
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not met within " + timeout);
      }
      try {
        Thread.sleep(25);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted", e);
      }
    }
  }
}
