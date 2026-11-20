package mfs.mw.admin.api.scheduler;

import mfs.mw.admin.api.service.CredentialExpiryService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Raises the partner credential expiry alerts (ticket 131: 30, 7 and 1 days before notAfter) on the
 * {@code mw.credentials.expiry.cron} schedule (daily 06:00 UTC). ShedLock-guarded: one instance at a
 * time; the alert ledger makes each threshold alert once even so.
 */
@Component
public class CredentialExpiryJob {

  public static final String LOCK_NAME = "credential-expiry";

  private final CredentialExpiryService service;

  public CredentialExpiryJob(CredentialExpiryService service) {
    this.service = service;
  }

  @Scheduled(cron = "${mw.credentials.expiry.cron}")
  @SchedulerLock(name = LOCK_NAME)
  public void alert() {
    service.raiseDue();
  }
}
