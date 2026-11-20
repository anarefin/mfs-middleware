package mfs.mw.admin.api.scheduler;

import mfs.mw.admin.api.service.ReconExceptionChangeHandler;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * REC-05 (M9 review): re-sends to mw-recon the approved {@code recon_exception} actions it has not acknowledged
 * ({@code ReconExceptionChangeHandler.retryDue}), on {@code mw.recon.action-retry-cron} (default every minute).
 * ShedLock-guarded: one instance at a time.
 */
@Component
public class ReconActionRetryJob {

  public static final String LOCK_NAME = "recon-action-retry";

  private final ReconExceptionChangeHandler handler;

  public ReconActionRetryJob(ReconExceptionChangeHandler handler) {
    this.handler = handler;
  }

  @Scheduled(cron = "${mw.recon.action-retry-cron:30 * * * * *}")
  @SchedulerLock(name = LOCK_NAME)
  public void retry() {
    handler.retryDue();
  }
}
