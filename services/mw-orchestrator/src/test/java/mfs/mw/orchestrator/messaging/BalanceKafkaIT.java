package mfs.mw.orchestrator.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.TestInfrastructure;
import mfs.mw.orchestrator.contracts.ContractSchemas;
import mfs.mw.orchestrator.dto.TxnRequest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * BALANCE (ADR D26) end to end over real Kafka and PostgreSQL against the orchestrator's REST core
 * stand-in ({@link CoreRestStub}, ticket 95a): request -> {@code GET /core/v1/balance/{walletId}} with
 * the assertion, txnId and idempotency key -> COMPLETED with
 * the balance on {@code mw.txn.status.v1}, which is never stored; a rejection ends FAILED. The
 * VALIDATING deadline is covered with a test clock in {@code service/BalanceSagaIT}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.orchestrator.consumers.enabled=true",
      "mw.orchestrator.timer.enabled=true",
      "mw.orchestrator.timer.period=200ms",
      "mw.outbox.relay.enabled=true",
      "mw.kafka.retry.delays=1s,1s,1s"
    })
@Import(TestInfrastructure.class)
@DirtiesContext
class BalanceKafkaIT {

  private static final String STATUS_SCHEMA = "events/mw-orchestrator/mw.txn.status.v1.schema.json";
  private static final String AUDIT_SCHEMA = "events/mw-audit-dw/mw.audit.event.v1.schema.json";

  private static final CoreRestStub core = CoreRestStub.start();

  @DynamicPropertySource
  static void coreApi(DynamicPropertyRegistry registry) {
    core.register(registry);
  }

  private final EventPublisher publisher;
  private final KafkaContainer kafka;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  BalanceKafkaIT(EventPublisher publisher, KafkaContainer kafka, JdbcTemplate jdbc, JsonMapper json) {
    this.publisher = publisher;
    this.kafka = kafka;
    this.jdbc = jdbc;
    this.json = json;
  }

  @AfterAll
  static void stopCore() {
    core.close();
  }

  private TxnRequest request(String wallet) {
    TxnRequest req = TestData.balance(TestData.txnId(), "key-" + TestData.txnId(), wallet);
    publisher.publish(
        "mw.txn.request.v1", req.txnId(),
        new EventMetadata(req.correlationId(), req.txnId(), req.idempotencyKey(), 1), req);
    return req;
  }

  private List<ConsumerRecord<String, String>> read(String topic, String txnId, int count) {
    return KafkaTestConsumer.read(
        kafka.getBootstrapServers(), topic, count, Duration.ofSeconds(60), r -> txnId.equals(r.key()));
  }

  private List<String> names(List<ConsumerRecord<String, String>> records) {
    return records.stream().map(r -> json.readTree(r.value()).path("status").asString()).toList();
  }

  private void assertValid(List<ConsumerRecord<String, String>> records, String schema) {
    for (ConsumerRecord<String, String> r : records) {
      assertThat(ContractSchemas.schema(schema).validate(json.readTree(r.value()))).as(r.value()).isEmpty();
      assertThat(KafkaTestConsumer.headers(r)).containsKeys(MwHeaders.REQUIRED.toArray(String[]::new));
    }
  }

  @Test
  void aBalanceCheckCompletesWithTheCoresBalanceOnTheStatus() {
    TxnRequest req = request("01711000001");

    List<ConsumerRecord<String, String>> got = read("mw.txn.status.v1", req.txnId(), 3);

    assertThat(names(got)).containsExactly("RECEIVED", "VALIDATING", "COMPLETED");
    assertValid(got, STATUS_SCHEMA);
    JsonNode done = json.readTree(got.get(2).value());
    assertThat(done.path("balance").path("available").path("valueMinor").asLong()).isEqualTo(CoreRestStub.AVAILABLE);
    assertThat(done.path("balance").path("asOf").asString()).isEqualTo("2026-11-16T08:15:31Z");
    assertThat(json.readTree(got.get(1).value()).has("balance")).isFalse();

    assertThat(core.requests(req.txnId())).singleElement().satisfies(c -> {
      assertThat(c.path()).isEqualTo("/core/v1/balance/01711000001?txnId=" + req.txnId());
      assertThat(c.headers()).containsEntry("x-txn-assertion", TestData.ASSERTION)
          .containsEntry("idempotency-key", req.idempotencyKey());
    });

    List<ConsumerRecord<String, String>> audits = read("mw.audit.event.v1", req.txnId(), 3);
    assertValid(audits, AUDIT_SCHEMA);
    assertThat(audits).extracting(ConsumerRecord::value)
        .noneMatch(v -> v.contains(String.valueOf(CoreRestStub.AVAILABLE)) || v.contains("balance"));
    String row = jdbc.queryForObject(
        "select row_to_json(t)::text from transaction t where txn_id = ?::uuid", String.class, req.txnId());
    assertThat(row).contains("COMPLETED").doesNotContain(String.valueOf(CoreRestStub.AVAILABLE));
  }

  @Test
  void aCoreRejectionEndsFailed() {
    TxnRequest req = request(CoreRestStub.UNKNOWN_WALLET);

    List<ConsumerRecord<String, String>> got = read("mw.txn.status.v1", req.txnId(), 3);

    assertThat(names(got)).containsExactly("RECEIVED", "VALIDATING", "FAILED");
    assertValid(got, STATUS_SCHEMA);
    JsonNode failed = json.readTree(got.get(2).value());
    assertThat(failed.path("error").path("code").asString()).isEqualTo("MW-CORE-WALLET_NOT_FOUND");
    assertThat(failed.has("balance")).isFalse();
  }
}
