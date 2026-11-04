package mfs.mw.service.template.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import mfs.mw.service.template.TestInfrastructure;
import mfs.mw.service.template.messaging.EventMetadata;
import mfs.mw.service.template.messaging.KafkaTestConsumer;
import mfs.mw.service.template.messaging.OutboxWriter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactional outbox (spec 6.4): the business row and the outbox row are written in one DB
 * transaction, and the relay route (on, as in production) publishes committed rows to Kafka
 * with the 6.1 headers and marks them sent. A rolled-back business transaction leaves no outbox
 * row and publishes nothing.
 *
 * <p>The context is closed afterwards so its relay cannot pick up rows of other tests (the
 * relay is off by default in tests, {@code src/test/resources/config/application.yml}).
 */
@Conformance
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"mw.outbox.relay.enabled=true", "mw.outbox.relay.period=200ms"})
@Import(TestInfrastructure.class)
@DirtiesContext
class OutboxConformanceIT {

  static final String TOPIC = "conformance.outbox.event.v1";

  private final OutboxWriter outbox;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  @Autowired
  OutboxConformanceIT(OutboxWriter outbox, JdbcTemplate jdbc, TransactionTemplate tx) {
    this.outbox = outbox;
    this.jdbc = jdbc;
    this.tx = tx;
  }

  @BeforeEach
  void businessTable() {
    jdbc.execute("create table if not exists conformance_business (id text primary key)");
  }

  /** A business operation: one business row and one event, in one transaction. */
  void businessOperation(String txnId, boolean failAfterWrites) {
    tx.executeWithoutResult(
        status -> {
          jdbc.update("insert into conformance_business (id) values (?)", txnId);
          outbox.append(
              TOPIC,
              txnId,
              new EventMetadata("corr-" + txnId, txnId, "idem-" + txnId, 1),
              Map.of("schemaVersion", 1, "eventId", "evt-" + txnId, "txnId", txnId));
          if (failAfterWrites) {
            throw new IllegalStateException("business rule failed after the writes");
          }
        });
  }

  List<ConsumerRecord<String, String>> published(String key, Duration timeout) {
    return KafkaTestConsumer.read(
        TestInfrastructure.kafkaBootstrapServers(), TOPIC, 1, timeout, r -> key.equals(r.key()));
  }

  int outboxRows(String key) {
    return jdbc.queryForObject("select count(*) from outbox where msg_key = ?", Integer.class, key);
  }

  @Test
  void committedEventIsRelayedWithItsHeadersAndMarkedSent() throws Exception {
    businessOperation("TXN-OUT-1", false);

    List<ConsumerRecord<String, String>> got = published("TXN-OUT-1", Duration.ofSeconds(20));
    assertThat(got).hasSize(1);
    ConsumerRecord<String, String> r = got.getFirst();
    assertThat(r.value()).contains("\"eventId\":\"evt-TXN-OUT-1\"");
    assertThat(KafkaTestConsumer.headers(r))
        .containsEntry("mw-correlation-id", "corr-TXN-OUT-1")
        .containsEntry("mw-txn-id", "TXN-OUT-1")
        .containsEntry("mw-idempotency-key", "idem-TXN-OUT-1")
        .containsEntry("mw-source", "mw-service-template")
        .containsEntry("mw-schema-version", "1")
        .containsKey("traceparent");

    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (jdbc.queryForObject(
                "select count(*) from outbox where msg_key = ? and sent_at is null",
                Integer.class,
                "TXN-OUT-1")
            > 0
        && System.nanoTime() < deadline) {
      Thread.sleep(100);
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox where msg_key = ? and sent_at is not null",
                Integer.class,
                "TXN-OUT-1"))
        .isEqualTo(1);
  }

  @Test
  void rolledBackBusinessTransactionWritesNoOutboxRowAndPublishesNothing() throws Exception {
    assertThatThrownBy(() -> businessOperation("TXN-OUT-RB", true))
        .isInstanceOf(IllegalStateException.class);

    assertThat(outboxRows("TXN-OUT-RB")).isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from conformance_business where id = ?", Integer.class, "TXN-OUT-RB"))
        .isZero();
    // Several relay periods (200 ms): nothing may be published for the rolled-back operation.
    Thread.sleep(1_000);
    assertThat(KafkaTestConsumer.readToEnd(TestInfrastructure.kafkaBootstrapServers(), TOPIC, r -> "TXN-OUT-RB".equals(r.key())))
        .isEmpty();
  }

  @Test
  void appendOutsideATransactionIsRefused() {
    assertThatThrownBy(
            () ->
                outbox.append(
                    TOPIC, "TXN-OUT-NOTX", new EventMetadata("c", "TXN-OUT-NOTX", "i", 1), Map.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("transaction");
    assertThat(outboxRows("TXN-OUT-NOTX")).isZero();
  }
}
