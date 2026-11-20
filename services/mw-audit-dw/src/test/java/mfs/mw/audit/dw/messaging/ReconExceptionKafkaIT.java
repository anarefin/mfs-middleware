package mfs.mw.audit.dw.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import mfs.mw.audit.dw.TestInfrastructure;
import mfs.mw.audit.dw.service.AuditChainVerifier;
import mfs.mw.audit.dw.service.ChainVerification;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code mw.recon.exception.v1} end to end over real Kafka and PostgreSQL (ticket 127b): each state change of a
 * recon exception lands in {@code audit.event} once (a redelivered eventId is stored once) in the audit hash chain,
 * and a record that is not such an event ends on the DLQ. The retry delays are shortened here.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"mw.audit-dw.consumers.enabled=true", "mw.kafka.retry.delays=100ms,100ms,100ms"})
@Import({TestInfrastructure.class, ReconExceptionKafkaIT.Topics.class})
@DirtiesContext
class ReconExceptionKafkaIT {

  static final String TOPIC = "mw.recon.exception.v1";
  static final String GROUP = "mw-audit-dw";

  @TestConfiguration(proxyBeanMethods = false)
  static class Topics {
    @Bean
    String reconExceptionTopics() {
      KafkaTestTopics.createWithRetries(TOPIC, GROUP, 3, 1);
      return TOPIC;
    }
  }

  private final EventPublisher publisher;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  ReconExceptionKafkaIT(EventPublisher publisher, JdbcTemplate jdbc, JsonMapper json) {
    this.publisher = publisher;
    this.jdbc = jdbc;
    this.json = json;
  }

  private void publish(String exceptionId, String eventId, String state, String actor) {
    String body =
        """
        {"schemaVersion":1,"eventId":"%s","exceptionId":"%s","state":"%s","exceptionClass":"STATUS_MISMATCH",
         "detail":"LEDGER_MISSING","owner":"ops","partnerId":"trust-bank","amountMinor":150000,
         "statementRef":"ref-1","detectedAt":"2026-11-19T20:15:00Z","changedAt":"2026-11-20T09:30:00Z",
         "correlationId":"corr-recon"%s}
        """
            .formatted(eventId, exceptionId, state,
                actor == null ? "" : ",\"actor\":\"" + actor + "\",\"action\":\"FORCE_COMMIT\"");
    publisher.publish(TOPIC, exceptionId, new EventMetadata("corr-recon", exceptionId, eventId, 1), json.readTree(body));
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
  void everyStateChangeIsStoredOnceInTheChain() {
    String exceptionId = UUID.randomUUID().toString();
    String opened = UUID.randomUUID().toString();
    publish(exceptionId, opened, "OPEN", null);
    publish(exceptionId, opened, "OPEN", null); // redelivery
    publish(exceptionId, UUID.randomUUID().toString(), "ACTIONED", "seed-ops-checker");
    publish(exceptionId, UUID.randomUUID().toString(), "RESOLVED", null);

    List<Map<String, Object>> rows =
        await(
            () -> jdbc.queryForList(
                "select action, actor_type, after->>'state' as state from event where resource = ? order by recorded_at, seq",
                "recon_exception/" + exceptionId),
            l -> l.size() >= 3);

    assertThat(rows).extracting(r -> r.get("action"))
        .containsExactly("recon.exception.open", "recon.exception.actioned", "recon.exception.resolved");
    assertThat(rows).extracting(r -> r.get("actor_type")).containsExactly("system", "user", "system");
    String month = jdbc.queryForObject("select month from event where resource = ? limit 1", String.class,
        "recon_exception/" + exceptionId);
    ChainVerification v = new AuditChainVerifier(jdbc).verify(month);
    assertThat(v.intact()).as(v.toString()).isTrue();
  }

  @Test
  void aRecordThatIsNotAReconExceptionEventEndsOnTheDlq() {
    String key = UUID.randomUUID().toString();
    publisher.publish(TOPIC, key, new EventMetadata("corr-bad", key, key, 1), json.readTree("{\"schemaVersion\":1}"));

    assertThat(
            KafkaTestConsumer.read(
                TestInfrastructure.kafkaBootstrapServers(),
                KafkaTestTopics.dlqTopic(TOPIC, GROUP),
                1,
                Duration.ofSeconds(60),
                r -> key.equals(r.key())))
        .hasSize(1);
  }
}
