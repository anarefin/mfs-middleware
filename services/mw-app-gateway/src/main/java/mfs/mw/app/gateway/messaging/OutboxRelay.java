package mfs.mw.app.gateway.messaging;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import mfs.mw.app.gateway.config.OutboxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes unsent outbox rows to Kafka and stamps {@code sent_at} (spec 6.4, 19.3), a batch at
 * a time and pipelined. Called by the relay route ({@code route/OutboxRelayRouteBuilder}) on a
 * timer thread, never on a Kafka consumer thread.
 *
 * <p>One batch, one transaction: claim up to {@code mw.outbox.relay.batch-size} unsent rows in
 * id order, hand every one to the idempotent producer without waiting ({@link
 * EventPublisher#publishStoredAsync}), wait for all acknowledgements, then mark the acknowledged
 * rows sent in one statement. {@link #relayPending} claims the next batch at once while batches
 * come back full, and stops (the route then waits one period) when the outbox is drained or a
 * send failed.
 *
 * <p>Guarantees:
 *
 * <ul>
 *   <li>At-least-once: a row is marked sent only after Kafka acknowledged it, in the transaction
 *       that locked it, so a crash or restart at any point re-publishes it; consumers drop the
 *       copy through their {@link Inbox}.
 *   <li>Order per key: rows are handed to the producer in id order on one thread, and Kafka's
 *       idempotent producer ({@code enable.idempotence=true}, {@code acks=all}, {@code
 *       max.in.flight.requests.per.connection} 5) keeps that order within the key's partition,
 *       retries included. A row Kafka did not acknowledge stays unsent, and so does every later
 *       row of the same key in the batch, acknowledged or not ({@link #markable}): the next batch
 *       starts again from the oldest unsent row of that key, so no row is ever marked sent ahead
 *       of an earlier row of its key. Other keys go on.
 *   <li>One relay at a time per service schema (a transaction-scoped advisory lock, released by
 *       commit, rollback or a dropped connection), rows locked {@code FOR UPDATE SKIP LOCKED}: a
 *       second instance or an overlapping run neither publishes a row twice concurrently nor
 *       reorders a key's events.
 * </ul>
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

  /** What Kafka answered for one row of a batch. */
  record Outcome(long id, String key, boolean acked) {}

  /**
   * Relays batches until one comes back short: the outbox is drained, a send failed, or another
   * relay holds the lock. Returns the number of rows marked sent.
   */
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
   * One transaction: lock up to {@code batchSize} unsent rows, send them all, wait for every
   * acknowledgement and mark the {@link #markable} rows sent in one statement. Returns the
   * number of rows marked sent; 0 when another relay holds the lock.
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
      if (rows.isEmpty()) {
        return 0;
      }
      List<CompletableFuture<Void>> acks = new ArrayList<>(rows.size());
      for (Row row : rows) {
        acks.add(send(row));
      }
      List<Outcome> outcomes = new ArrayList<>(rows.size());
      Row firstFailed = null;
      Throwable firstError = null;
      int failures = 0;
      for (int i = 0; i < rows.size(); i++) {
        Row row = rows.get(i);
        Throwable error = awaitAck(acks.get(i));
        if (error != null) {
          failures++;
          if (firstFailed == null) {
            firstFailed = row;
            firstError = error;
          }
        }
        outcomes.add(new Outcome(row.id(), row.key(), error == null));
      }
      List<Long> done = markable(outcomes);
      if (!done.isEmpty()) {
        markSent(done);
      }
      if (firstFailed != null) {
        log.atWarn()
            .addKeyValue("event", "outbox.relay.failed")
            .addKeyValue("topic", firstFailed.topic())
            .addKeyValue("outboxId", firstFailed.id())
            .addKeyValue("failed", failures)
            .addKeyValue("heldBack", rows.size() - done.size() - failures)
            .addKeyValue("errorClass", firstError.getClass().getName())
            .log("outbox relay: unacknowledged rows and later rows of their keys stay unsent and are retried");
      }
      return done.size();
    });
    return sent == null ? 0 : sent;
  }

  /**
   * The ids of a batch's rows that may be marked sent, in id order: every acknowledged row with no
   * unacknowledged row of the same key before it. {@code inIdOrder} is the batch in claim (id)
   * order.
   */
  static List<Long> markable(List<Outcome> inIdOrder) {
    Set<String> heldBack = new HashSet<>();
    List<Long> ids = new ArrayList<>(inIdOrder.size());
    for (Outcome o : inIdOrder) {
      if (heldBack.contains(o.key())) {
        continue;
      }
      if (o.acked()) {
        ids.add(o.id());
      } else {
        heldBack.add(o.key());
      }
    }
    return ids;
  }

  private CompletableFuture<Void> send(Row row) {
    try {
      return publisher.publishStoredAsync(
          row.topic(),
          row.key(),
          json.readValue(row.headers(), HEADERS),
          json.writeValueAsString(json.readTree(row.payload()))); // jsonb text -> compact JSON
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  /**
   * Waits for one acknowledgement: null when Kafka acknowledged the record, else the cause. The
   * producer completes every send within its {@code delivery.timeout.ms}.
   */
  private static Throwable awaitAck(CompletableFuture<Void> ack) {
    try {
      ack.join();
      return null;
    } catch (CompletionException e) {
      return e.getCause() == null ? e : e.getCause();
    } catch (RuntimeException e) {
      return e;
    }
  }

  /** Marks the acknowledged rows of a batch sent, in one statement of the relay's transaction. */
  protected void markSent(List<Long> ids) {
    jdbc.update(
        con -> {
          PreparedStatement ps =
              con.prepareStatement("update outbox set sent_at = now() where id = any(?)");
          ps.setArray(1, con.createArrayOf("bigint", ids.toArray()));
          return ps;
        });
  }
}
