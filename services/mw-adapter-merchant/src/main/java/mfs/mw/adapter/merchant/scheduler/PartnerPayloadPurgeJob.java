package mfs.mw.adapter.merchant.scheduler;

import mfs.mw.adapter.merchant.service.PartnerPayloadRetentionService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link PartnerPayloadRetentionService#purge()} on the {@code mw.retention.cron} schedule,
 * ShedLock-guarded (ticket 97).
 */
@Component
public class PartnerPayloadPurgeJob {

  public static final String LOCK_NAME = "partner-payload-purge";

  private final PartnerPayloadRetentionService retention;

  public PartnerPayloadPurgeJob(PartnerPayloadRetentionService retention) {
    this.retention = retention;
  }

  @Scheduled(cron = "${mw.retention.cron}")
  @SchedulerLock(name = LOCK_NAME)
  public void purge() {
    retention.purge();
  }
}
