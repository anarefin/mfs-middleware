package mfs.mw.simulators.scheduler;

import mfs.mw.simulators.service.files.DwDropService;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls the Data Warehouse drop folder every {@code sim.files.dw.poll} (ticket 123): {@link DwDropService#run()}.
 * ShedLock-guarded; only with {@code sim.files.enabled} and the mock-control profile.
 */
@Component
@Profile(MockControl.PROFILE)
@ConditionalOnProperty(name = "sim.files.enabled", havingValue = "true")
public class DwDropValidatorJob {

  private static final Logger log = LoggerFactory.getLogger(DwDropValidatorJob.class);

  private final DwDropService dw;

  public DwDropValidatorJob(DwDropService dw) {
    this.dw = dw;
  }

  @Scheduled(fixedDelayString = "${sim.files.dw.poll}", initialDelayString = "${sim.files.dw.poll}")
  @SchedulerLock(name = "sim-dw-drop-validator")
  public void poll() {
    try {
      dw.run();
      failing = false;
    } catch (RuntimeException e) {
      // one warning per outage (partner-sftp runs in profile tester only), then debug until it is back
      (failing ? log.atDebug() : log.atWarn()).addKeyValue("event", "sim.files.poll_failed").addKeyValue("partnerId", "dw")
          .log("DW poll failed: {}", e.getMessage());
      failing = true;
    }
  }

  private volatile boolean failing;
}
