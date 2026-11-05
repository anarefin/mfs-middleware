package mfs.mw.portal.api.scheduler;

import mfs.mw.portal.api.service.BulkReconcileService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link BulkReconcileService#reconcile()} every {@code mw.portal-api.bulk.reconcile.period}
 * (M3 gate decision). ShedLock-guarded, so one instance asks the orchestrator at a time. Runs on a
 * scheduler thread, never on a Kafka listener thread.
 */
@Component
@ConditionalOnProperty(name = "mw.portal-api.bulk.reconcile.enabled", havingValue = "true", matchIfMissing = true)
public class BulkReconcileJob {

  public static final String LOCK_NAME = "bulk-reconcile";

  private final BulkReconcileService reconcile;

  public BulkReconcileJob(BulkReconcileService reconcile) {
    this.reconcile = reconcile;
  }

  @Scheduled(fixedDelayString = "${mw.portal-api.bulk.reconcile.period}")
  @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT10M")
  public void reconcile() {
    reconcile.reconcile();
  }
}
