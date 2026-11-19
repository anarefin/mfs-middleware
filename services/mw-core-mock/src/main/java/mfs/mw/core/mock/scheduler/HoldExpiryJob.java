package mfs.mw.core.mock.scheduler;

import mfs.mw.core.mock.service.HoldExpiry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs {@link HoldExpiry#expireDue()} on {@code mw.coremock.hold-expiry.cron} (ticket 94, FRS 7.3), one instance at a time. */
@Component
public class HoldExpiryJob {

  public static final String LOCK_NAME = "hold-expiry";

  private final HoldExpiry expiry;

  public HoldExpiryJob(HoldExpiry expiry) {
    this.expiry = expiry;
  }

  @Scheduled(cron = "${mw.coremock.hold-expiry.cron}")
  @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT5M")
  public void run() {
    expiry.expireDue();
  }
}
