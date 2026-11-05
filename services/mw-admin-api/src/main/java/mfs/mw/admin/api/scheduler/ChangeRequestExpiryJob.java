package mfs.mw.admin.api.scheduler;

import mfs.mw.admin.api.service.ChangeRequestService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expires change requests not decided within 72 h (spec 15.3, ticket 37) on the {@code
 * mw.change-requests.expiry-cron} schedule. ShedLock-guarded: one instance at a time.
 */
@Component
public class ChangeRequestExpiryJob {

  public static final String LOCK_NAME = "change-request-expiry";

  private final ChangeRequestService service;

  public ChangeRequestExpiryJob(ChangeRequestService service) {
    this.service = service;
  }

  @Scheduled(cron = "${mw.change-requests.expiry-cron}")
  @SchedulerLock(name = LOCK_NAME)
  public void expire() {
    service.expireDue();
  }
}
