package mfs.mw.orchestrator.scheduler;

import mfs.mw.orchestrator.service.routing.RoutingCatalog;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Re-reads the routing tables every 30 seconds (ticket 98, FRS PLT-041) so a change that the
 * config listener missed is still used without a restart. Health stays in memory across the reload.
 */
@Component
public class RoutingReloadJob {

  public static final String LOCK_NAME = "routing-reload";

  private final RoutingCatalog catalog;

  public RoutingReloadJob(RoutingCatalog catalog) {
    this.catalog = catalog;
  }

  @Scheduled(fixedDelayString = "${mw.orchestrator.routing.reload-interval:30s}")
  @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT30S", lockAtLeastFor = "PT0S")
  public void reload() {
    catalog.reload();
  }
}
