package mfs.mw.app.gateway.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Inbox deduplication for Kafka consumers (spec 6.4, 19.3): Kafka and the outbox relay deliver
 * at least once, so a consumer runs its side effect through {@link #processOnce}, which records
 * {@code (consumer, eventId)} in the same transaction as the side effect. A redelivered event
 * is then a no-op, and a side effect that throws rolls the record back, so the retry topic's
 * next attempt still applies it.
 *
 * <p>Concurrent deliveries of one event are safe: the second insert waits on the primary key
 * until the first transaction ends and then finds the row.
 */
@Component
public class Inbox {

  private static final Logger log = LoggerFactory.getLogger(Inbox.class);

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  public Inbox(JdbcTemplate jdbc, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.tx = tx;
  }

  /**
   * Runs {@code sideEffect} unless {@code consumer} already applied {@code eventId}, in one
   * transaction (joining the caller's if there is one). The side effect must write through this
   * service's DataSource so it commits or rolls back with the inbox record.
   *
   * @return true when the side effect ran, false for a duplicate
   */
  public boolean processOnce(String consumer, String eventId, Runnable sideEffect) {
    if (eventId == null || eventId.isBlank()) {
      throw new IllegalArgumentException("eventId is required for inbox deduplication");
    }
    Boolean applied =
        tx.execute(
            status -> {
              int inserted =
                  jdbc.update(
                      "insert into inbox (consumer, event_id) values (?, ?) on conflict do nothing",
                      consumer,
                      eventId);
              if (inserted == 0) {
                return false;
              }
              sideEffect.run();
              return true;
            });
    if (!Boolean.TRUE.equals(applied)) {
      log.atInfo()
          .addKeyValue("event", "kafka.inbox.duplicate")
          .addKeyValue("consumer", consumer)
          .addKeyValue("eventId", eventId)
          .log("duplicate event ignored");
      return false;
    }
    return true;
  }
}
