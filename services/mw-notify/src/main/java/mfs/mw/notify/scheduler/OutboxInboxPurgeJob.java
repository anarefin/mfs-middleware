package mfs.mw.notify.scheduler;

import mfs.mw.notify.service.RetentionService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link RetentionService#purge()} on the {@code mw.retention.cron} schedule (spec 4.4, 6.4,
 * 16.3). ShedLock-guarded: one instance runs it at a time, and a run while another holds the
 * lock returns at once.
 */
@Component
public class OutboxInboxPurgeJob {

  public static final String LOCK_NAME = "outbox-inbox-purge";

  private final RetentionService retention;

  public OutboxInboxPurgeJob(RetentionService retention) {
    this.retention = retention;
  }

  @Scheduled(cron = "${mw.retention.cron}")
  @SchedulerLock(name = LOCK_NAME)
  public void purge() {
    retention.purge();
  }
}
