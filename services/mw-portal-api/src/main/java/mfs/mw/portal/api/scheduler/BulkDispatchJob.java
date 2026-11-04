package mfs.mw.portal.api.scheduler;

import mfs.mw.portal.api.service.BulkExecutionService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link BulkExecutionService#dispatch()} every {@code mw.portal-api.bulk.dispatch.period}
 * (ticket 34). ShedLock-guarded, so one instance streams children at a time and the configured
 * rate holds for the whole service.
 */
@Component
@ConditionalOnProperty(name = "mw.portal-api.bulk.dispatch.enabled", havingValue = "true", matchIfMissing = true)
public class BulkDispatchJob {

  public static final String LOCK_NAME = "bulk-dispatch";

  private final BulkExecutionService execution;

  public BulkDispatchJob(BulkExecutionService execution) {
    this.execution = execution;
  }

  @Scheduled(fixedDelayString = "${mw.portal-api.bulk.dispatch.period}")
  @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT1M")
  public void dispatch() {
    execution.dispatch();
  }
}
