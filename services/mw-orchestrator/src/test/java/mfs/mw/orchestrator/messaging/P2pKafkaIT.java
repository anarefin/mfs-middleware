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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The orchestrator end to end over real Kafka and PostgreSQL, against its own core stub: a P2P
 * request on {@code mw.txn.request.v1} reaches COMPLETED, a core rejection reaches REJECTED, and
 * a core that never answers PostTransfer ends UNKNOWN through the timer sweep.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.orchestrator.consumers.enabled=true",
      "mw.orchestrator.timer.enabled=true",
      "mw.orchestrator.timer.period=200ms",
      "mw.orchestrator.posting-timeout=2s",
      "mw.outbox.relay.enabled=true",
      "mw.kafka.retry.delays=1s,1s,1s"
    })
@Import(TestInfrastructure.class)
@DirtiesContext
class P2pKafkaIT {

  private static final String STATUS_SCHEMA = "events/mw-orchestrator/mw.txn.status.v1.schema.json";
  private static final String COMMAND_SCHEMA = "events/mw-orchestrator/core.command.v1.schema.json";
  private static final String AUDIT_SCHEMA = "events/mw-audit-dw/mw.audit.event.v1.schema.json";

  private static CoreStub core;

  private final EventPublisher publisher;
  private final KafkaContainer kafka;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  P2pKafkaIT(EventPublisher publisher, KafkaContainer kafka, JdbcTemplate jdbc, JsonMapper json) {
    this.publisher = publisher;
    this.kafka = kafka;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeAll
  static void startCore(@Autowired KafkaContainer kafka) {
    core = new CoreStub(kafka.getBootstrapServers());
  }

  @AfterAll
  static void stopCore() {
    core.close();
  }

  private TxnRequest request(long amount) {
    TxnRequest req = TestData.p2p(TestData.txnId(), "key-" + TestData.txnId(), amount);
    publisher.publish(
        "mw.txn.request.v1",
        req.txnId(),
        new EventMetadata(req.correlationId(), req.txnId(), req.idempotencyKey(), 1),
        req);
    return req;
  }

  private List<ConsumerRecord<String, String>> statuses(String txnId, int count) {
    return KafkaTestConsumer.read(
        kafka.getBootstrapServers(), "mw.txn.status.v1", count, Duration.ofSeconds(60), r -> txnId.equals(r.key()));
  }

  private List<String> names(List<ConsumerRecord<String, String>> records) {
    return records.stream().map(r -> json.readTree(r.value()).path("status").asString()).toList();
  }

  private void assertValid(List<ConsumerRecord<String, String>> records) {
    for (ConsumerRecord<String, String> r : records) {
      assertThat(ContractSchemas.schema(STATUS_SCHEMA).validate(json.readTree(r.value())))
          .as(r.value())
          .isEmpty();
      assertThat(KafkaTestConsumer.headers(r)).containsKeys(MwHeaders.REQUIRED.toArray(String[]::new));
    }
  }

  @Test
  void aP2pRequestReachesCompleted() {
    TxnRequest req = request(25_000);

    List<ConsumerRecord<String, String>> got = statuses(req.txnId(), 4);

    assertThat(names(got)).containsExactly("RECEIVED", "VALIDATING", "POSTING", "COMPLETED");
    assertValid(got);
    JsonNode done = json.readTree(got.get(3).value());
    assertThat(done.path("fee").path("valueMinor").asLong()).isEqualTo(500);
    assertThat(done.path("coreRef").asString()).startsWith("STUB-");
    List<JsonNode> mine = core.commands().stream().filter(c -> req.txnId().equals(c.path("txnId").asString())).toList();
    assertThat(mine).extracting(c -> c.path("commandId").asString())
        .containsExactly(req.txnId() + ":validate", req.txnId() + ":post");
    for (JsonNode c : mine) {
      assertThat(ContractSchemas.schema(COMMAND_SCHEMA).validate(c)).isEmpty();
    }
    assertThat(jdbc.queryForObject("select status from transaction where txn_id = ?::uuid", String.class, req.txnId()))
        .isEqualTo("COMPLETED");

    // ticket 12: one audit event per state, on mw.audit.event.v1, valid against its contract
    List<ConsumerRecord<String, String>> audits =
        KafkaTestConsumer.read(
            kafka.getBootstrapServers(), "mw.audit.event.v1", 4, Duration.ofSeconds(60), r -> req.txnId().equals(r.key()));
    assertThat(audits)
        .extracting(r -> json.readTree(r.value()).path("after").path("status").asString())
        .containsExactly("RECEIVED", "VALIDATING", "POSTING", "COMPLETED");
    for (ConsumerRecord<String, String> r : audits) {
      assertThat(ContractSchemas.schema(AUDIT_SCHEMA).validate(json.readTree(r.value()))).as(r.value()).isEmpty();
      assertThat(KafkaTestConsumer.headers(r)).containsKeys(MwHeaders.REQUIRED.toArray(String[]::new));
    }
  }

  @Test
  void aCoreRejectionReachesRejected() {
    TxnRequest req = request(CoreStub.REJECT_AMOUNT);

    List<ConsumerRecord<String, String>> got = statuses(req.txnId(), 3);

    assertThat(names(got)).containsExactly("RECEIVED", "VALIDATING", "REJECTED");
    assertValid(got);
    assertThat(json.readTree(got.get(2).value()).path("error").path("code").asString())
        .isEqualTo("MW-CORE-INSUFFICIENT_BALANCE");
  }

  @Tag("slow-wait")
  @Test
  void aCoreThatNeverAnswersPostTransferEndsUnknownAfterTheDeadline() {
    TxnRequest req = request(CoreStub.SILENT_AMOUNT);

    List<ConsumerRecord<String, String>> got = statuses(req.txnId(), 4);

    assertThat(names(got)).containsExactly("RECEIVED", "VALIDATING", "POSTING", "UNKNOWN");
    assertValid(got);
  }

  @Test
  void aRepeatedRequestKeyIsAnsweredWithoutASecondTransaction() {
    TxnRequest first = request(25_000);
    assertThat(names(statuses(first.txnId(), 4))).last().isEqualTo("COMPLETED");
    TxnRequest again = TestData.p2p(TestData.txnId(), first.idempotencyKey(), 25_000);
    publisher.publish(
        "mw.txn.request.v1",
        again.txnId(),
        new EventMetadata(again.correlationId(), again.txnId(), again.idempotencyKey(), 1),
        again);

    List<ConsumerRecord<String, String>> got = statuses(first.txnId(), 5);

    assertThat(names(got)).last().isEqualTo("COMPLETED");
    JsonNode answer = json.readTree(got.get(4).value());
    assertThat(answer.path("attributes").path("requestTxnId").asString()).isEqualTo(again.txnId());
    assertThat(jdbc.queryForObject("select count(*) from transaction where txn_id = ?::uuid", Long.class, again.txnId()))
        .isZero();
  }
}
