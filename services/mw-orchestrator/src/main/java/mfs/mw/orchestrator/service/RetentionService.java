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

  private static final String TERMINAL = "('COMPLETED','FAILED','REJECTED','REVERSED')";

  /** Purges both tables, terminal saga rows past their window, and expired idempotency keys. */
  public Purged purge() {
    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    int steps = deleteWhere(
        "delete from saga_step where id in (select s.id from saga_step s"
            + " left join transaction t on t.txn_id = s.txn_id"
            + " where s.at < ? and (t.txn_id is null or t.status in " + TERMINAL + ")"
            + " order by s.id limit ?)",
        now.minus(properties.sagaSteps()));
    int timers = deleteWhere(
        "delete from saga_timer where (txn_id, step) in (select s.txn_id, s.step from saga_timer s"
            + " left join transaction t on t.txn_id = s.txn_id"
            + " where t.txn_id is null or (t.status in " + TERMINAL + " and t.updated_at < ?)"
            + " order by s.txn_id limit ?)",
        now.minus(properties.transactions()));
    int partners = deleteWhere(
        "delete from partner_call where id in (select c.id from partner_call c"
            + " left join transaction t on t.txn_id = c.txn_id"
            + " where c.sent_at < ? and (t.txn_id is null or t.status in " + TERMINAL + ")"
            + " order by c.id limit ?)",
        now.minus(properties.partnerCalls()));
    int core = deleteWhere(
        "delete from core_call where id in (select c.id from core_call c"
            + " left join transaction t on t.txn_id = c.txn_id"
            + " where c.sent_at < ? and (t.txn_id is null or t.status in " + TERMINAL + ")"
            + " order by c.id limit ?)",
        now.minus(properties.coreCalls()));
    int txns = deleteWhere(
        "delete from transaction where txn_id in (select txn_id from transaction"
            + " where status in " + TERMINAL + " and updated_at < ? order by txn_id limit ?)",
        now.minus(properties.transactions()));
    int idem = deleteWhere(
        "delete from idempotency where (channel, initiator_id, idem_key) in ("
            + "select channel, initiator_id, idem_key from idempotency where expires_at < ?"
            + " order by expires_at limit ?)",
        now);
    Purged purged =
        new Purged(
            deleteInBatches(PURGE_OUTBOX, now.minus(properties.outbox())),
            deleteInBatches(PURGE_INBOX, now.minus(properties.inbox())));
    log.atInfo()
        .addKeyValue("event", "outbox.inbox.purged")
        .addKeyValue("outboxRows", purged.outboxRows())
        .addKeyValue("inboxRows", purged.inboxRows())
        .addKeyValue("transactions", txns)
        .addKeyValue("sagaSteps", steps)
        .addKeyValue("sagaTimers", timers)
        .addKeyValue("partnerCalls", partners)
        .addKeyValue("coreCalls", core)
        .addKeyValue("idempotency", idem)
        .addKeyValue("idempotencyWindow", properties.idempotency())
        .log("purged processed outbox, inbox and terminal saga rows");
    return purged;
  }

  private int deleteWhere(String sql, OffsetDateTime cutoff) {
    return deleteInBatches(sql, cutoff);
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
