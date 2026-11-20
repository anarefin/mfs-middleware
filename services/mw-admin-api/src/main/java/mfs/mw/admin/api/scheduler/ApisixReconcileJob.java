package mfs.mw.admin.api.scheduler;

import mfs.mw.admin.api.service.ChannelClientProjection;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Re-projects the whole channel client registry onto APISIX (ticket 100a) on the {@code
 * mw.apisix.reconcile-cron} schedule: repairs a projection that failed after an approval, drift made
 * by hand, and a reset APISIX (etcd volume lost). ShedLock-guarded: one instance at a time. Does
 * nothing while {@code mw.apisix.enabled} is false.
 */
@Component
public class ApisixReconcileJob {

  public static final String LOCK_NAME = "apisix-consumer-reconcile";

  private final ChannelClientProjection projection;

  public ApisixReconcileJob(ChannelClientProjection projection) {
    this.projection = projection;
  }

  @Scheduled(cron = "${mw.apisix.reconcile-cron}")
  @SchedulerLock(name = LOCK_NAME)
  public void reconcile() {
    projection.reconcile();
  }
}
