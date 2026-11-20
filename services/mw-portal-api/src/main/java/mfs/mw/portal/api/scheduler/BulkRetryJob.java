package mfs.mw.portal.api.scheduler;

import mfs.mw.portal.api.service.BulkRetryService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link BulkRetryService#verify()} every {@code mw.portal-api.bulk.retry.period} (ticket 129c).
 * ShedLock-guarded, so one instance checks the rows of an approved retry at a time. Runs on a
 * scheduler thread, never on a Kafka listener thread.
 */
@Component
@ConditionalOnProperty(name = "mw.portal-api.bulk.retry.enabled", havingValue = "true", matchIfMissing = true)
public class BulkRetryJob {

  public static final String LOCK_NAME = "bulk-retry";

  private final BulkRetryService retry;

  public BulkRetryJob(BulkRetryService retry) {
    this.retry = retry;
  }

  @Scheduled(fixedDelayString = "${mw.portal-api.bulk.retry.period}")
  @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT10M")
  public void verify() {
    retry.verify();
  }
}
