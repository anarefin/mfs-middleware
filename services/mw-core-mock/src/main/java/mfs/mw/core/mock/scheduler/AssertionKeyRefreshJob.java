package mfs.mw.core.mock.scheduler;

import mfs.mw.core.mock.security.AssertionKeys;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fetches mw-auth's assertion key set at start (before the service reports ready) and every {@code
 * mw.coremock.assertion.refresh-period} (ticket 71). Not ShedLock-guarded on purpose: the set is
 * an in-memory cache every instance needs for itself.
 */
@Component
public class AssertionKeyRefreshJob implements ApplicationRunner {

  private final AssertionKeys keys;

  public AssertionKeyRefreshJob(AssertionKeys keys) {
    this.keys = keys;
  }

  @Override
  public void run(ApplicationArguments args) {
    keys.refresh();
  }

  @Scheduled(
      initialDelayString = "${mw.coremock.assertion.refresh-period}",
      fixedDelayString = "${mw.coremock.assertion.refresh-period}")
  public void refresh() {
    keys.refresh();
  }
}
