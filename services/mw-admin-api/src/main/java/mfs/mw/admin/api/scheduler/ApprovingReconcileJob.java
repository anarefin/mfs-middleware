package mfs.mw.admin.api.scheduler;

import mfs.mw.admin.api.service.ChangeRequestService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Resolves claim-first approvals (ticket 75) whose claim lease ({@code
 * mw.change-requests.approving-lease}) is over, from the other service's state of the change ({@code
 * ChangeRequestService.reconcileApproving}), on {@code mw.change-requests.approving-reconcile-cron}.
 * ShedLock-guarded: one instance at a time.
 */
@Component
public class ApprovingReconcileJob {

  public static final String LOCK_NAME = "change-request-approving-reconcile";

  private final ChangeRequestService service;

  public ApprovingReconcileJob(ChangeRequestService service) {
    this.service = service;
  }

  @Scheduled(cron = "${mw.change-requests.approving-reconcile-cron}")
  @SchedulerLock(name = LOCK_NAME)
  public void reconcile() {
    service.reconcileApproving();
  }
}
