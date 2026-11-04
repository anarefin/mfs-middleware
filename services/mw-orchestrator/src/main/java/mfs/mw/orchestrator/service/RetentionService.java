package mfs.mw.orchestrator.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import mfs.mw.orchestrator.config.RetentionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Deletes processed outbox and inbox rows past retention (spec 6.4, 16.3): sent outbox rows
 * older than {@code mw.retention.outbox} and inbox rows older than {@code mw.retention.inbox}.
 * Unsent outbox rows are never deleted. Each batch of {@code mw.retention.batch-size} rows, in
 * key order, is its own short statement, so no long lock is held on either table. Called by
 * {@code scheduler/OutboxInboxPurgeJob}, which makes sure one instance runs it at a time.
 */
@Service
public class RetentionService {

  private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

  private static final String PURGE_OUTBOX =
      "delete from outbox where id in (select id from outbox where sent_at < ?"
          + " order by id limit ?)";
  private static final String PURGE_INBOX =
      "delete from inbox where (consumer, event_id) in (select consumer, event_id from inbox"
          + " where processed_at < ? order by consumer, event_id limit ?)";

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final RetentionProperties properties;

  public RetentionService(JdbcTemplate jdbc, Clock clock, RetentionProperties properties) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.properties = properties;
  }

  /** Purges both tables and returns how many rows each lost. */
  public Purged purge() {
    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    Purged purged =
        new Purged(
            deleteInBatches(PURGE_OUTBOX, now.minus(properties.outbox())),
            deleteInBatches(PURGE_INBOX, now.minus(properties.inbox())));
    log.atInfo()
        .addKeyValue("event", "outbox.inbox.purged")
        .addKeyValue("outboxRows", purged.outboxRows())
        .addKeyValue("inboxRows", purged.inboxRows())
        .log("purged processed outbox and inbox rows");
    return purged;
  }

  private int deleteInBatches(String sql, OffsetDateTime cutoff) {
    int batchSize = properties.batchSize();
    int total = 0;
    int n;
    do {
      n = jdbc.update(sql, cutoff, batchSize);
      total += n;
    } while (n == batchSize);
    return total;
  }

  /** Rows deleted by one purge run. */
  public record Purged(int outboxRows, int inboxRows) {}
}
