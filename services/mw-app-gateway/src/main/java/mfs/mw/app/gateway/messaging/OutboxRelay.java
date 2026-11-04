package mfs.mw.app.gateway.messaging;

import java.util.List;
import java.util.Map;
import mfs.mw.app.gateway.config.OutboxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes unsent outbox rows to Kafka and stamps {@code sent_at} (spec 6.4, 19.3). Called by
 * the relay route ({@code route/OutboxRelayRouteBuilder}) on a timer thread, never on a Kafka
 * consumer thread.
 *
 * <p>Guarantees: a row is marked sent only after Kafka acknowledged it, in the transaction that
 * locked it, so a crash or restart at any point re-publishes it (at-least-once); consumers drop
 * the copy through their {@link Inbox}. Only one relay runs at a time per service schema (a
 * transaction-scoped advisory lock, released by commit, rollback or a dropped connection), and
 * rows are locked {@code FOR UPDATE SKIP LOCKED} and published in id order, so a second instance
 * or an overlapping run neither publishes a row twice concurrently nor reorders a key's events.
 */
@Component
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
  private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final EventPublisher publisher;
  private final JsonMapper json;
  private final int batchSize;

  public OutboxRelay(
      JdbcTemplate jdbc,
      TransactionTemplate tx,
      EventPublisher publisher,
      JsonMapper json,
      OutboxProperties properties) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.publisher = publisher;
    this.json = json;
    this.batchSize = properties.batchSize();
  }

  private record Row(long id, String topic, String key, String headers, String payload) {}

  /** Relays batches until no unsent row is left (or another relay holds the lock). */
  public int relayPending() {
    int total = 0;
    int n;
    do {
      n = relayBatch();
      total += n;
    } while (n == batchSize);
    return total;
  }

  /**
   * One transaction: lock up to {@code batchSize} unsent rows, publish each, mark it sent. A
   * publish failure ends the batch; rows sent before it stay marked, the rest are retried on the
   * next run. Returns the number of rows marked sent; 0 when another relay holds the lock.
   */
  public int relayBatch() {
    Integer sent = tx.execute(status -> {
      Boolean locked =
          jdbc.queryForObject(
              "select pg_try_advisory_xact_lock(hashtext(current_schema() || '.outbox'))",
              Boolean.class);
      if (!Boolean.TRUE.equals(locked)) {
        return 0;
      }
      List<Row> rows =
          jdbc.query(
              "select id, topic, msg_key, headers::text, payload::text from outbox"
                  + " where sent_at is null order by id limit ? for update skip locked",
              (rs, i) ->
                  new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)),
              batchSize);
      int done = 0;
      for (Row row : rows) {
        try {
          publisher.publishStored(
              row.topic(),
              row.key(),
              json.readValue(row.headers(), HEADERS),
              json.writeValueAsString(json.readTree(row.payload()))); // jsonb text -> compact JSON
        } catch (RuntimeException e) {
          log.atWarn()
              .addKeyValue("event", "outbox.relay.failed")
              .addKeyValue("topic", row.topic())
              .addKeyValue("outboxId", row.id())
              .addKeyValue("errorClass", e.getClass().getName())
              .log("outbox relay stopped; row stays unsent and is retried");
          break;
        }
        markSent(row.id());
        done++;
      }
      return done;
    });
    return sent == null ? 0 : sent;
  }

  /** Marks one published row sent, in the relay's transaction. */
  protected void markSent(long id) {
    jdbc.update("update outbox set sent_at = now() where id = ?", id);
  }
}
