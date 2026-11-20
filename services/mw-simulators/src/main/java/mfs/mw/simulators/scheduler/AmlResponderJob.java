package mfs.mw.simulators.scheduler;

import mfs.mw.simulators.service.files.AmlMockService;
import mfs.mw.simulators.service.mockcontrol.MockControl;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls the AML inbox every {@code sim.files.aml.poll} (ticket 123): {@link AmlMockService#run()}. ShedLock-guarded;
 * only with {@code sim.files.enabled} and the mock-control profile. A file host that cannot be reached is a warning
 * and the next poll tries again.
 */
@Component
@Profile(MockControl.PROFILE)
@ConditionalOnProperty(name = "sim.files.enabled", havingValue = "true")
public class AmlResponderJob {

  private static final Logger log = LoggerFactory.getLogger(AmlResponderJob.class);

  private final AmlMockService aml;

  public AmlResponderJob(AmlMockService aml) {
    this.aml = aml;
  }

  @Scheduled(fixedDelayString = "${sim.files.aml.poll}", initialDelayString = "${sim.files.aml.poll}")
  @SchedulerLock(name = "sim-aml-responder")
  public void poll() {
    try {
      aml.run();
      failing = false;
    } catch (RuntimeException e) {
      // one warning per outage (partner-sftp runs in profile tester only), then debug until it is back
      (failing ? log.atDebug() : log.atWarn()).addKeyValue("event", "sim.files.poll_failed").addKeyValue("partnerId", "aml")
          .log("AML poll failed: {}", e.getMessage());
      failing = true;
    }
  }

  private volatile boolean failing;
}
