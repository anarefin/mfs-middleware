package mfs.mw.core.mock.service;

import java.time.Clock;
import mfs.mw.core.mock.config.AssertionProperties;
import mfs.mw.core.mock.repository.UsedAssertionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Deletes used jtis {@code mw.coremock.assertion.used-retention} after their {@code exp} (ticket
 * 71), in batches; an assertion that old fails its expiry check anyway, so forgetting its jti
 * cannot let it be used again.
 */
@Service
public class UsedAssertionPurge {

  private static final Logger log = LoggerFactory.getLogger(UsedAssertionPurge.class);

  private final UsedAssertionRepository used;
  private final AssertionProperties props;
  private final Clock clock;

  public UsedAssertionPurge(UsedAssertionRepository used, AssertionProperties props, Clock clock) {
    this.used = used;
    this.props = props;
    this.clock = clock;
  }

  /** Purges until a batch comes back short; how many rows went. */
  public int purge() {
    int total = 0;
    int n;
    do {
      n = used.purge(clock.instant().minus(props.usedRetention()), props.purgeBatchSize());
      total += n;
    } while (n == props.purgeBatchSize());
    log.atInfo().addKeyValue("event", "core.assertion.purged").addKeyValue("rows", total).log("used assertions purged");
    return total;
  }
}
