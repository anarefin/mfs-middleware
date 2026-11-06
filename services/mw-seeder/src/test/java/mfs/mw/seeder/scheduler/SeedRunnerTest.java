package mfs.mw.seeder.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import mfs.mw.seeder.exception.RemoteCallException;
import mfs.mw.seeder.exception.SeedFailedException;
import mfs.mw.seeder.service.SeedContext;
import mfs.mw.seeder.service.SeedStep;
import mfs.mw.seeder.service.StepResult;
import org.junit.jupiter.api.Test;

/** The run harness (ticket 57): order, retry on 503/504/connect errors, timeout, fail-fast, exit code. */
class SeedRunnerTest {

  private final List<String> calls = new ArrayList<>();

  private SeedStep step(String id, Function<Integer, StepResult> body) {
    AtomicInteger attempt = new AtomicInteger();
    return new SeedStep() {
      @Override
      public String id() {
        return id;
      }

      @Override
      public StepResult run(SeedContext ctx) {
        calls.add(id);
        return body.apply(attempt.incrementAndGet());
      }
    };
  }

  private static SeedRunner runner(List<SeedStep> steps, Duration timeout, Map<String, Duration> perStep) {
    return new SeedRunner(steps, new RunnerSettings(timeout, perStep, Duration.ofMillis(5), Duration.ofMillis(20)));
  }

  @Test
  void runsEveryStepInTheGivenOrderAndReportsEachOutcome() {
    SeedRunner r =
        runner(
            List.of(
                step("a", n -> StepResult.unchanged(1)),
                step("b", n -> StepResult.created(2)),
                step("c", n -> StepResult.unchanged(0))),
            Duration.ofSeconds(5),
            Map.of());

    Map<String, StepResult> results = r.runAll();

    assertThat(calls).containsExactly("a", "b", "c");
    assertThat(results.keySet()).containsExactly("a", "b", "c");
    assertThat(results.get("b").outcome()).isEqualTo(StepResult.Outcome.CREATED);
    assertThat(results.get("a").outcome()).isEqualTo(StepResult.Outcome.UNCHANGED);
  }

  @Test
  void retriesA503A504AndAConnectErrorThenSucceeds() {
    SeedRunner r =
        runner(
            List.of(
                step(
                    "flaky",
                    n ->
                        switch (n) {
                          case 1 -> throw new RemoteCallException(503, "GET /x answered 503");
                          case 2 -> throw new RemoteCallException(504, "GET /x answered 504");
                          case 3 -> throw RemoteCallException.unreachable("GET /x", new java.net.ConnectException());
                          default -> StepResult.created(1);
                        })),
            Duration.ofSeconds(5),
            Map.of());

    assertThat(r.runAll().get("flaky").outcome()).isEqualTo(StepResult.Outcome.CREATED);
    assertThat(calls).hasSize(4);
  }

  @Test
  void aNonRetryableErrorStopsTheRunAtOnceAndLaterStepsNeverRun() {
    SeedRunner r =
        runner(
            List.of(
                step("first", n -> StepResult.unchanged(1)),
                step("broken", n -> {
                  throw new RemoteCallException(400, "POST /y answered 400");
                }),
                step("never", n -> StepResult.created(1))),
            Duration.ofSeconds(5),
            Map.of());

    assertThatThrownBy(r::runAll)
        .isInstanceOf(SeedFailedException.class)
        .hasMessageContaining("broken")
        .satisfies(e -> assertThat(((SeedFailedException) e).getExitCode()).isEqualTo(1));
    assertThat(calls).containsExactly("first", "broken");
  }

  @Test
  void aStepThatKeepsAnswering503FailsOnceItsTimeoutHasPassed() {
    SeedRunner r =
        runner(
            List.of(step("down", n -> {
              throw new RemoteCallException(503, "GET /z answered 503");
            })),
            Duration.ofSeconds(5),
            Map.of("down", Duration.ofMillis(150)));

    long start = System.nanoTime();
    assertThatThrownBy(r::runAll).isInstanceOf(SeedFailedException.class).hasMessageContaining("down");
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    assertThat(calls.size()).isGreaterThan(1);
  }

  @Test
  void aStepThatHangsIsCutOffAtItsTimeout() {
    SeedRunner r =
        runner(
            List.of(step("hangs", n -> {
              try {
                Thread.sleep(Duration.ofSeconds(30));
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return StepResult.unchanged(0);
            })),
            Duration.ofMillis(100),
            Map.of());

    long start = System.nanoTime();
    assertThatThrownBy(r::runAll).isInstanceOf(SeedFailedException.class).hasMessageContaining("timed out");
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
  }

  @Test
  void stepResultsAddUpToCreatedThenUpdatedThenUnchanged() {
    assertThat(StepResult.unchanged(2).plus(StepResult.updated(1)).outcome()).isEqualTo(StepResult.Outcome.UPDATED);
    assertThat(StepResult.updated(1).plus(StepResult.created(1)).outcome()).isEqualTo(StepResult.Outcome.CREATED);
    assertThat(StepResult.unchanged(3).plus(StepResult.unchanged(1))).isEqualTo(StepResult.unchanged(4));
    assertThat(StepResult.created(1).plus(StepResult.updated(2)).plus(StepResult.unchanged(3)).toString())
        .isEqualTo("created=1 updated=2 unchanged=3");
  }
}
