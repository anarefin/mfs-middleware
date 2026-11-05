package mfs.mw.adapter.bank.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import mfs.mw.adapter.bank.TestInfrastructure;
import mfs.mw.adapter.bank.scheduler.OutboxInboxPurgeJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbox and inbox purge (spec 6.4, 16.3): a scheduled job deletes sent outbox rows older than
 * {@code mw.retention.outbox} and inbox rows older than {@code mw.retention.inbox} (7 days by
 * default), in batches. Unsent outbox rows are never purged, however old. The job is
 * ShedLock-guarded: while another instance holds the lock it does nothing. (ShedLock keeps UTC
 * database time in {@code timestamp} columns, hence {@code timezone('utc', now())} below.)
 */
@Conformance
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    // batch size 2 so the 3 old rows of each table need more than one batch
    properties = "mw.retention.batch-size=2")
@Import(TestInfrastructure.class)
class OutboxInboxPurgeConformanceIT {

  private final OutboxInboxPurgeJob job;
  private final JdbcTemplate jdbc;

  @Autowired
  OutboxInboxPurgeConformanceIT(OutboxInboxPurgeJob job, JdbcTemplate jdbc) {
    this.job = job;
    this.jdbc = jdbc;
  }

  @BeforeEach
  void rows() {
    holdLock("-1 minute"); // released (ShedLock keeps its row; deleting it would confuse ShedLock)
    jdbc.update("delete from outbox where topic like 'purge.%'");
    jdbc.update("delete from inbox where consumer = 'purge-test'");
    for (String[] r :
        new String[][] {
          {"purge.sent-old-1", "8 days", "8 days"},
          {"purge.sent-old-2", "30 days", "29 days"},
          {"purge.sent-old-3", "9 days", "7 days 1 hour"},
          {"purge.sent-recent", "8 days", "6 days"},
          {"purge.unsent-old", "60 days", null},
          {"purge.unsent-recent", "1 hour", null}
        }) {
      jdbc.update(
          "insert into outbox (topic, msg_key, headers, payload, created_at, sent_at) values"
              + " (?, 'k', '{}'::jsonb, '{}'::jsonb, now() - ?::interval, now() - ?::interval)",
          r[0],
          r[1],
          r[2]);
    }
    for (String[] r :
        new String[][] {
          {"evt-old-1", "8 days"}, {"evt-old-2", "40 days"}, {"evt-old-3", "7 days 1 hour"},
          {"evt-recent", "6 days 23 hours"}
        }) {
      jdbc.update(
          "insert into inbox (consumer, event_id, processed_at) values ('purge-test', ?, now() - ?::interval)",
          r[0],
          r[1]);
    }
  }

  /** Another instance's lock on the purge job, until now + {@code interval} (UTC DB time). */
  void holdLock(String interval) {
    jdbc.update(
        "insert into shedlock (name, lock_until, locked_at, locked_by)"
            + " values (?, timezone('utc', now()) + ?::interval, timezone('utc', now()), 'other-instance')"
            + " on conflict (name) do update set lock_until = excluded.lock_until,"
            + " locked_by = excluded.locked_by",
        OutboxInboxPurgeJob.LOCK_NAME,
        interval);
  }

  List<String> outboxTopics() {
    return jdbc.queryForList(
        "select topic from outbox where topic like 'purge.%' order by topic", String.class);
  }

  List<String> inboxEvents() {
    return jdbc.queryForList(
        "select event_id from inbox where consumer = 'purge-test' order by event_id", String.class);
  }

  @Test
  void sentOutboxAndInboxRowsOlderThanRetentionArePurgedRecentAndUnsentRowsRemain() {
    job.purge();

    assertThat(outboxTopics())
        .containsExactly("purge.sent-recent", "purge.unsent-old", "purge.unsent-recent");
    assertThat(inboxEvents()).containsExactly("evt-recent");
  }

  @Test
  void purgeDoesNothingWhileAnotherInstanceHoldsTheLock() {
    holdLock("10 minutes");

    job.purge();

    assertThat(outboxTopics()).hasSize(6);
    assertThat(inboxEvents()).hasSize(4);
  }

  @Test
  void twoConcurrentRunsPurgeOnceAndOnlyOneHoldsTheLock() throws Exception {
    // the first run blocks inside its first delete batch (a row lock taken by this test), so
    // the second run starts while the first still holds the ShedLock lock
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CompletableFuture<Void> blocker =
        CompletableFuture.runAsync(
            () ->
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()))
                    .executeWithoutResult(
                        s -> {
                          jdbc.queryForList(
                              "select id from outbox where topic = 'purge.sent-old-1' for update");
                          locked.countDown();
                          try {
                            release.await(30, TimeUnit.SECONDS);
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                          }
                        }));
    assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
    CompletableFuture<Void> first = CompletableFuture.runAsync(job::purge);
    awaitLockHeld();
    // second instance: the lock is held, so it returns at once without touching a row (were it
    // to delete, it would block on the same row lock and time out here)
    CompletableFuture.runAsync(job::purge).get(5, TimeUnit.SECONDS);
    assertThat(outboxTopics()).hasSize(6);
    release.countDown();
    blocker.get(30, TimeUnit.SECONDS);
    first.get(30, TimeUnit.SECONDS);

    assertThat(outboxTopics())
        .containsExactly("purge.sent-recent", "purge.unsent-old", "purge.unsent-recent");
    assertThat(inboxEvents()).containsExactly("evt-recent");
  }

  void awaitLockHeld() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (jdbc.queryForObject(
            "select count(*) from shedlock where name = ? and lock_until > timezone('utc', now())",
            Integer.class,
            OutboxInboxPurgeJob.LOCK_NAME)
        == 0) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("purge never took the lock");
      }
      Thread.sleep(50);
    }
  }
}
