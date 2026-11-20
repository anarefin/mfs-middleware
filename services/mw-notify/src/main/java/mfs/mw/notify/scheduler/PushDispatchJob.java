package mfs.mw.notify.scheduler;

import mfs.mw.notify.service.PushDispatchService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the PUSH dispatch cycle every {@code mw.notify.dispatch.period} (ticket 110), apart from the SMS and
 * email cycle ({@link DispatchJob}): its own lock and, with virtual threads on, its own scheduler thread, so
 * a push server that is slow or down never delays an SMS (FL-14).
 */
@Component
@ConditionalOnProperty(name = "mw.notify.dispatch.enabled", havingValue = "true", matchIfMissing = true)
public class PushDispatchJob {

  public static final String LOCK_NAME = "notify-push-dispatch";

  private final PushDispatchService dispatcher;

  public PushDispatchJob(PushDispatchService dispatcher) {
    this.dispatcher = dispatcher;
  }

  @Scheduled(fixedDelayString = "${mw.notify.dispatch.period:200ms}")
  @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT5M")
  public void run() {
    dispatcher.dispatchDue();
  }
}
