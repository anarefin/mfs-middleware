package mfs.mw.service.template.messaging;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes an event to the transactional outbox (spec 6.4): call it from a {@code @Transactional}
 * service method, in the same transaction as the business row, so both commit or neither does.
 * {@link OutboxRelay} publishes it after the commit. Refuses to run outside a transaction,
 * because then the event could be stored without the business change or the other way round.
 */
@Component
public class OutboxWriter {

  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  private final EventPublisher publisher;

  public OutboxWriter(JdbcTemplate jdbc, JsonMapper json, EventPublisher publisher) {
    this.jdbc = jdbc;
    this.json = json;
    this.publisher = publisher;
  }

  /** Stores {@code payload} (as JSON) for {@code topic}, partition key {@code key}. */
  public void append(String topic, String key, EventMetadata meta, Object payload) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "outbox append needs the business transaction: call it from a @Transactional service method");
    }
    Map<String, String> headers = publisher.headersFor(topic, meta);
    jdbc.update(
        "insert into outbox (topic, msg_key, headers, payload) values (?, ?, ?::jsonb, ?::jsonb)",
        topic,
        key,
        json.writeValueAsString(headers),
        json.writeValueAsString(payload));
  }
}
