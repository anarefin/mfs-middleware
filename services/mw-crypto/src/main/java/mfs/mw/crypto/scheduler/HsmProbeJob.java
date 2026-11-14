package mfs.mw.crypto.scheduler;

import mfs.mw.crypto.service.HsmHealthService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Probes the HSM every {@code mw.crypto.hsm-probe-period} ({@link HsmHealthService}: gauge {@code
 * crypto.hsm.up}, alert event {@code crypto.hsm.down}). Per instance on purpose: each instance
 * reports its own HSM sessions, so no ShedLock.
 */
@Component
public class HsmProbeJob {

  private final HsmHealthService health;

  public HsmProbeJob(HsmHealthService health) {
    this.health = health;
  }

  @Scheduled(initialDelay = 0, fixedDelayString = "${mw.crypto.hsm-probe-period}")
  public void probe() {
    health.probe();
  }
}
