package mfs.mw.crypto.scheduler;

import mfs.mw.crypto.service.KeyBootstrapService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dev only (ticket 62): on start, and every {@code mw.crypto.bootstrap-period} after, creates the
 * missing key of every bootstrap purpose ({@link KeyBootstrapService}), so the stack comes up usable
 * and recovers from an HSM that was not up yet. ShedLock-guarded (one instance at a time); a run
 * with nothing missing is one query. Off unless {@code mw.crypto.bootstrap-keys=true} (production:
 * keys only from the ceremony, ticket 75).
 */
@Component
@ConditionalOnProperty(name = "mw.crypto.bootstrap-keys", havingValue = "true")
public class KeyBootstrap {

  public static final String LOCK_NAME = "key-bootstrap";

  private final KeyBootstrapService bootstrap;

  public KeyBootstrap(KeyBootstrapService bootstrap) {
    this.bootstrap = bootstrap;
  }

  @Scheduled(initialDelay = 0, fixedDelayString = "${mw.crypto.bootstrap-period}")
  @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT5M", lockAtLeastFor = "PT0S")
  public void run() {
    bootstrap.ensureKeys();
  }
}
