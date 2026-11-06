package mfs.mw.seeder.scheduler;

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import mfs.mw.seeder.exception.RemoteCallException;
import mfs.mw.seeder.exception.SeedFailedException;
import mfs.mw.seeder.service.SeedContext;
import mfs.mw.seeder.service.SeedStep;
import mfs.mw.seeder.service.StepResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Runs the seed steps in order (ticket 57, BitsPay {@code SeedRunner}). Each step runs on its own
 * virtual thread with its timeout; a retryable failure (503, 504, no answer) runs the whole step
 * again after a back-off (doubling, capped) until the timeout; anything else, or the timeout,
 * stops the run with {@link SeedFailedException} (exit code 1). Later steps never run.
 */
public class SeedRunner {

  private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

  private final List<SeedStep> steps;
  private final RunnerSettings settings;

  public SeedRunner(List<SeedStep> steps, RunnerSettings settings) {
    Set<String> ids = new HashSet<>();
    for (SeedStep s : steps) {
      if (!ids.add(s.id())) {
        throw new IllegalArgumentException("two seed steps with id " + s.id());
      }
    }
    this.steps = List.copyOf(steps);
    this.settings = settings;
  }

  /** Runs every step; returns each step's result in run order. */
  public Map<String, StepResult> runAll() {
    String runId = UUID.randomUUID().toString();
    SeedContext ctx = new SeedContext();
    Map<String, StepResult> results = new LinkedHashMap<>();
    long start = System.nanoTime();
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      for (SeedStep step : steps) {
        results.put(step.id(), runStep(executor, runId, step, ctx));
      }
    } finally {
      // No blocking close(): a step that ignores interruption must not keep the JVM from exiting 1.
      executor.shutdownNow();
    }
    log.atInfo()
        .addKeyValue("event", "seed.run.completed")
        .addKeyValue("durationMs", millis(start))
        .log("seed run {} completed: {} steps", runId, results.size());
    return results;
  }

  private StepResult runStep(ExecutorService executor, String runId, SeedStep step, SeedContext ctx) {
    Duration timeout = settings.timeoutOf(step.id());
    long start = System.nanoTime();
    long deadline = start + timeout.toNanos();
    log.atInfo().addKeyValue("event", "seed.step.started").log("seed step {} started", step.id());
    Future<StepResult> future =
        executor.submit(
            () -> {
              MDC.put("correlationId", runId);
              try {
                return attempt(step, ctx, deadline);
              } finally {
                MDC.remove("correlationId");
              }
            });
    try {
      StepResult result = future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
      log.atInfo()
          .addKeyValue("event", "seed.step.completed")
          .addKeyValue("durationMs", millis(start))
          .addKeyValue("outcome", result.outcome().name())
          .log("seed step {} {}: {}", step.id(), result.outcome().name().toLowerCase(), result.toString());
      return result;
    } catch (TimeoutException e) {
      future.cancel(true);
      throw failed(step, "timed out after " + timeout, e, start);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      throw failed(step, cause.getMessage(), cause, start);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      throw failed(step, "interrupted", e, start);
    }
  }

  private StepResult attempt(SeedStep step, SeedContext ctx, long deadline) throws InterruptedException {
    Duration backoff = settings.initialBackoff();
    int attempt = 1;
    while (true) {
      try {
        return step.run(ctx);
      } catch (RemoteCallException e) {
        if (!e.retryable() || System.nanoTime() + backoff.toNanos() >= deadline) {
          throw e;
        }
        log.atWarn()
            .addKeyValue("event", "seed.step.retry")
            .log("seed step {} attempt {} not done yet, retrying in {}: {}", step.id(), attempt, backoff, e.getMessage());
        Thread.sleep(backoff);
        Duration doubled = backoff.multipliedBy(2);
        backoff = doubled.compareTo(settings.maxBackoff()) > 0 ? settings.maxBackoff() : doubled;
        attempt++;
      }
    }
  }

  private static SeedFailedException failed(SeedStep step, String reason, Throwable cause, long start) {
    log.atError()
        .addKeyValue("event", "seed.step.failed")
        .addKeyValue("durationMs", millis(start))
        .log("seed step {} failed: {}", step.id(), reason);
    return new SeedFailedException(step.id(), reason, cause);
  }

  private static long millis(long startNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
  }
}
