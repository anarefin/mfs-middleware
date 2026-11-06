package mfs.mw.seeder.scheduler;

import java.time.Duration;
import java.util.Map;

/**
 * How the runner times and retries steps: {@code stepTimeout} unless {@code stepTimeouts} names the
 * step; a retryable failure waits {@code initialBackoff}, doubling up to {@code maxBackoff}.
 */
public record RunnerSettings(
    Duration stepTimeout, Map<String, Duration> stepTimeouts, Duration initialBackoff, Duration maxBackoff) {

  public RunnerSettings {
    stepTimeouts = stepTimeouts == null ? Map.of() : Map.copyOf(stepTimeouts);
  }

  Duration timeoutOf(String stepId) {
    return stepTimeouts.getOrDefault(stepId, stepTimeout);
  }
}
