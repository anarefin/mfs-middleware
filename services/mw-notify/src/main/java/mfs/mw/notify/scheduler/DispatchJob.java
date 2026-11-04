package mfs.mw.notify.scheduler;

import mfs.mw.notify.service.DispatchService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the SMS dispatch cycle every {@code mw.notify.dispatch.period} (spec 10). ShedLock-guarded:
 * the SMPP binds live in one instance (19.3), and a second instance would only wait.
 */
@Component
@ConditionalOnProperty(name = "mw.notify.dispatch.enabled", havingValue = "true", matchIfMissing = true)
public class DispatchJob {

  public static final String LOCK_NAME = "notify-dispatch";

  private final DispatchService dispatcher;

  public DispatchJob(DispatchService dispatcher) {
    this.dispatcher = dispatcher;
  }

  @Scheduled(fixedDelayString = "${mw.notify.dispatch.period:200ms}")
  @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT5M")
  public void run() {
    dispatcher.cycle();
  }
}
