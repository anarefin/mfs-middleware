package mfs.mw.adapter.interop.scheduler;

import mfs.mw.adapter.interop.partner.route.PartnerHealthMonitor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Active partner health (ticket 97): {@link PartnerHealthMonitor#run()} every {@code
 * mw.partners.health.interval} (default 30 s) after a run ends, ShedLock-guarded so one instance
 * probes and publishes. {@code mw.partners.health.enabled=false} switches it off (tests).
 */
@Component
@ConditionalOnProperty(name = "mw.partners.health.enabled", havingValue = "true", matchIfMissing = true)
public class PartnerHealthJob {

  public static final String LOCK_NAME = "partner-health";

  private final PartnerHealthMonitor monitor;

  public PartnerHealthJob(PartnerHealthMonitor monitor) {
    this.monitor = monitor;
  }

  @Scheduled(
      fixedDelayString = "${mw.partners.health.interval:30s}",
      initialDelayString = "${mw.partners.health.interval:30s}")
  @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT5M")
  public void run() {
    monitor.run();
  }
}
