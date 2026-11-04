package mfs.mw.orchestrator.scheduler;

import mfs.mw.orchestrator.service.ManualReviewAgeingService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link ManualReviewAgeingService#check()} once a day on {@code
 * mw.orchestrator.manual-review-ageing-cron} (UTC; {@code -} switches it off). ShedLock-guarded:
 * one instance reports.
 */
@Component
public class ManualReviewAgeingJob {

  public static final String LOCK_NAME = "manual-review-ageing";

  private final ManualReviewAgeingService ageing;

  public ManualReviewAgeingJob(ManualReviewAgeingService ageing) {
    this.ageing = ageing;
  }

  @Scheduled(cron = "${mw.orchestrator.manual-review-ageing-cron:0 0 6 * * *}", zone = "UTC")
  @SchedulerLock(name = LOCK_NAME)
  public void check() {
    ageing.check();
  }
}
