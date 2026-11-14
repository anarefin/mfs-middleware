package mfs.mw.core.mock.scheduler;

import mfs.mw.core.mock.service.UsedAssertionPurge;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs {@link UsedAssertionPurge#purge()} on {@code mw.coremock.assertion.purge-cron} (ticket 71). */
@Component
public class UsedAssertionPurgeJob {

  public static final String LOCK_NAME = "used-assertion-purge";

  private final UsedAssertionPurge purge;

  public UsedAssertionPurgeJob(UsedAssertionPurge purge) {
    this.purge = purge;
  }

  @Scheduled(cron = "${mw.coremock.assertion.purge-cron}")
  @SchedulerLock(name = LOCK_NAME)
  public void purge() {
    purge.purge();
  }
}
