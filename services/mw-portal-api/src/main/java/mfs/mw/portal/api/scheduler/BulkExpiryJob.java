package mfs.mw.portal.api.scheduler;

import mfs.mw.portal.api.service.BatchExpiryService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link BatchExpiryService#expirePending()} every {@code mw.portal-api.bulk.expiry.period}
 * (M3 gate decision: pending batches expire after 72 h). ShedLock-guarded.
 */
@Component
@ConditionalOnProperty(name = "mw.portal-api.bulk.expiry.enabled", havingValue = "true", matchIfMissing = true)
public class BulkExpiryJob {

  public static final String LOCK_NAME = "bulk-expiry";

  private final BatchExpiryService expiry;

  public BulkExpiryJob(BatchExpiryService expiry) {
    this.expiry = expiry;
  }

  @Scheduled(fixedDelayString = "${mw.portal-api.bulk.expiry.period}")
  @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT10M")
  public void expire() {
    expiry.expirePending();
  }
}
