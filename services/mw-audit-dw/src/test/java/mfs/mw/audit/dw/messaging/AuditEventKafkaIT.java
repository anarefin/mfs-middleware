package mfs.mw.audit.dw.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import mfs.mw.audit.dw.TestInfrastructure;
import mfs.mw.audit.dw.route.RetryTopicRouteBuilder;
import mfs.mw.audit.dw.service.AuditChainVerifier;
import mfs.mw.audit.dw.service.ChainVerification;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code mw.audit.event.v1} end to end over real Kafka and PostgreSQL: what a producer publishes
 * lands in {@code audit.event}, a redelivered auditId once, and a record that is not an audit
 * event ends on the DLQ instead of blocking the partition.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"mw.audit-dw.consumers.enabled=true", "mw.kafka.retry.delays=1s,1s,1s"})
@Import(TestInfrastructure.class)
@DirtiesContext
class AuditEventKafkaIT {

  static final String TOPIC = "mw.audit.event.v1";

  private final EventPublisher publisher;
  private final KafkaContainer kafka;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  AuditEventKafkaIT(EventPublisher publisher, KafkaContainer kafka, JdbcTemplate jdbc, JsonMapper json) {
    this.publisher = publisher;
    this.kafka = kafka;
    this.jdbc = jdbc;
    this.json = json;
  }

  private void publish(String txnId, String auditId, String from, String to) {
    String body =
        """
        {"schemaVersion":1,"auditId":"%s","txnId":"%s","correlationId":"corr-k",
         "actor":{"type":"system","id":"mw-orchestrator"},"action":"txn.state.changed",
         "resource":"txn/%s","before":{"status":"%s"},"after":{"status":"%s"},
         "outcome":"SUCCESS","at":"2026-11-03T08:15:31Z","service":"mw-orchestrator"}
        """
            .formatted(auditId, txnId, txnId, from, to);
    publisher.publish(TOPIC, txnId, new EventMetadata("corr-k", txnId, auditId, 1), json.readTree(body));
  }

  private static <T> T await(Supplier<T> value, java.util.function.Predicate<T> done) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
    T v = value.get();
    while (!done.test(v) && Instant.now().isBefore(deadline)) {
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
      v = value.get();
    }
    return v;
  }

  @Test
  void publishedEventsAreStoredOnceEachAndChained() {
    String txn = "01933b8e-7c1a-7b2e-9f3d-" + UUID.randomUUID().toString().substring(24);
    String first = UUID.randomUUID().toString();
    publish(txn, first, "RECEIVED", "VALIDATING");
    publish(txn, first, "RECEIVED", "VALIDATING"); // redelivery
    publish(txn, UUID.randomUUID().toString(), "VALIDATING", "POSTING");
    publish(txn, UUID.randomUUID().toString(), "POSTING", "COMPLETED");

    // Same key, same partition, handled in order: once the last event is stored, so was the copy.
    List<String> states =
        await(
            () -> jdbc.queryForList(
                "select after->>'status' from event where txn_id = ?::uuid order by recorded_at, seq", String.class, txn),
            l -> l.size() >= 3);

    assertThat(states).containsExactly("VALIDATING", "POSTING", "COMPLETED");
    String month = jdbc.queryForObject("select month from event where txn_id = ?::uuid limit 1", String.class, txn);
    ChainVerification v = new AuditChainVerifier(jdbc).verify(month);
    assertThat(v.intact()).as(v.toString()).isTrue();
  }

  @Test
  void aRecordThatIsNotAnAuditEventEndsOnTheDlq() {
    String key = UUID.randomUUID().toString();
    publisher.publish(TOPIC, key, new EventMetadata("corr-bad", key, key, 1), json.readTree("{\"schemaVersion\":1}"));

    assertThat(
            KafkaTestConsumer.read(
                kafka.getBootstrapServers(), RetryTopicRouteBuilder.dlqTopic(TOPIC, "mw-audit-dw"), 1, Duration.ofSeconds(60), r -> key.equals(r.key())))
        .hasSize(1);
  }
}
