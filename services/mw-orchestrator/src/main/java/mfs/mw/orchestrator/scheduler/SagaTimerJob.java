package mfs.mw.orchestrator.scheduler;

import mfs.mw.orchestrator.service.SagaTimerService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps due saga timers every {@code mw.orchestrator.timer.period} (default 1 s, spec 7.1).
 * ShedLock-guarded: one instance sweeps at a time; the sweep itself is also safe under
 * concurrency ({@code SKIP LOCKED}).
 */
@Component
@ConditionalOnProperty(name = "mw.orchestrator.timer.enabled", havingValue = "true", matchIfMissing = true)
public class SagaTimerJob {

  public static final String LOCK_NAME = "saga-timer-sweep";

  private final SagaTimerService timers;

  public SagaTimerJob(SagaTimerService timers) {
    this.timers = timers;
  }

  @Scheduled(fixedDelayString = "${mw.orchestrator.timer.period:1s}")
  @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT30S", lockAtLeastFor = "PT0S")
  public void sweep() {
    timers.sweep();
  }
}
