package mfs.mw.service.template.scheduler;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import mfs.mw.service.template.config.RetentionProperties;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Purges processed outbox and inbox rows (spec 6.4, 16.3): sent outbox rows older than {@code
 * mw.outbox.retention} and inbox rows older than {@code mw.inbox.retention}. Unsent outbox rows
 * are never deleted. Each batch of {@code mw.purge.batch-size} rows is its own short statement,
 * so no long lock is held on either table. ShedLock-guarded: one instance runs it at a time, and
 * a run while another holds the lock returns at once.
 */
@Component
public class OutboxInboxPurgeJob {

  public static final String LOCK_NAME = "outbox-inbox-purge";

  private static final Logger log = LoggerFactory.getLogger(OutboxInboxPurgeJob.class);

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final RetentionProperties properties;

  public OutboxInboxPurgeJob(JdbcTemplate jdbc, Clock clock, RetentionProperties properties) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.properties = properties;
  }

  @Scheduled(
      fixedDelayString = "${mw.purge.period}",
      initialDelayString = "${mw.purge.period}")
  @SchedulerLock(name = LOCK_NAME)
  public void purge() {
    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    int outbox =
        deleteInBatches(
            "delete from outbox where id in (select id from outbox where sent_at < ?"
                + " order by id limit ?)",
            now.minus(properties.outbox().retention()));
    int inbox =
        deleteInBatches(
            "delete from inbox where (consumer, event_id) in (select consumer, event_id from inbox"
                + " where processed_at < ? limit ?)",
            now.minus(properties.inbox().retention()));
    log.atInfo()
        .addKeyValue("event", "outbox.inbox.purged")
        .addKeyValue("outboxRows", outbox)
        .addKeyValue("inboxRows", inbox)
        .log("purged processed outbox and inbox rows");
  }

  private int deleteInBatches(String sql, OffsetDateTime cutoff) {
    int batchSize = properties.purge().batchSize();
    int total = 0;
    int n;
    do {
      n = jdbc.update(sql, cutoff, batchSize);
      total += n;
    } while (n == batchSize);
    return total;
  }
}
