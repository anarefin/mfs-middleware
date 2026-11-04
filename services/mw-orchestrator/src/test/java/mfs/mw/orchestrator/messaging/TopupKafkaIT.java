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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * TOPUP over real Kafka and PostgreSQL (ticket 17) against the orchestrator's own core and partner
 * stubs: the partner reply route closes the loop, a success commits and a refusal releases. The
 * partner timeout and the first inquiry slot are shortened to seconds (ticket 18).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.orchestrator.consumers.enabled=true",
      "mw.orchestrator.timer.enabled=true",
      "mw.orchestrator.timer.period=200ms",
      "mw.orchestrator.partner-timeout=2s",
      "mw.orchestrator.inquiry.schedule=1s",
      "mw.outbox.relay.enabled=true",
      "mw.kafka.retry.delays=1s,1s,1s"
    })
@Import(TestInfrastructure.class)
@DirtiesContext
class TopupKafkaIT {

  private static final String STATUS_SCHEMA = "events/mw-orchestrator/mw.txn.status.v1.schema.json";
  private static final String PARTNER_SCHEMA = "events/mw-orchestrator/mw.partner.request.v1.schema.json";

  private static CoreStub core;
  private static PartnerStub partner;

  private final EventPublisher publisher;
  private final KafkaContainer kafka;
  private final JsonMapper json;

  @Autowired
  TopupKafkaIT(EventPublisher publisher, KafkaContainer kafka, JsonMapper json) {
    this.publisher = publisher;
    this.kafka = kafka;
    this.json = json;
  }

  @BeforeAll
  static void startStubs(@Autowired KafkaContainer kafka) {
    core = new CoreStub(kafka.getBootstrapServers());
    partner = new PartnerStub(kafka.getBootstrapServers());
  }

  @AfterAll
  static void stopStubs() {
    core.close();
    partner.close();
  }

  private TxnRequest request(String msisdn) {
    TxnRequest req = TestData.topup(TestData.txnId(), "key-" + TestData.txnId(), 2_000, msisdn);
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

  private List<String> commandTypes(String txnId) {
    return core.commands().stream()
        .filter(c -> txnId.equals(c.path("txnId").asString()))
        .map(c -> c.path("commandType").asString())
        .toList();
  }

  @Test
  void aTopupThePartnerAcceptsIsCommitted() {
    TxnRequest req = request("01711000002");

    List<ConsumerRecord<String, String>> got = statuses(req.txnId(), 6);

    assertThat(names(got))
        .containsExactly("RECEIVED", "VALIDATING", "HOLDING", "PARTNER_PENDING", "COMMITTING", "COMPLETED");
    for (ConsumerRecord<String, String> r : got) {
      assertThat(ContractSchemas.schema(STATUS_SCHEMA).validate(json.readTree(r.value()))).as(r.value()).isEmpty();
    }
    assertThat(json.readTree(got.get(5).value()).path("partnerRef").asString()).startsWith("OCS-STUB-");
    assertThat(commandTypes(req.txnId())).containsExactly("ValidateTransaction", "HoldFunds", "CommitHold");
    List<JsonNode> sent = partner.requests().stream().filter(p -> req.txnId().equals(p.path("txnId").asString())).toList();
    assertThat(sent).hasSize(1);
    assertThat(ContractSchemas.schema(PARTNER_SCHEMA).validate(sent.get(0))).isEmpty();
    assertThat(KafkaTestConsumer.headers(got.get(5))).containsKeys(MwHeaders.REQUIRED.toArray(String[]::new));
  }

  @Test
  void aLostPartnerReplyIsRecoveredByStatusInquiry() {
    TxnRequest req = request("01711000888");

    List<ConsumerRecord<String, String>> got = statuses(req.txnId(), 7);

    assertThat(names(got))
        .containsExactly(
            "RECEIVED", "VALIDATING", "HOLDING", "PARTNER_PENDING", "UNKNOWN", "COMMITTING", "COMPLETED");
    assertThat(commandTypes(req.txnId())).containsExactly("ValidateTransaction", "HoldFunds", "CommitHold");
    List<String> operations =
        partner.requests().stream()
            .filter(p -> req.txnId().equals(p.path("txnId").asString()))
            .map(p -> p.path("payload").path("operation").asString())
            .toList();
    assertThat(operations).containsExactly("PAY", "STATUS_QUERY");
    for (JsonNode sent : partner.requests()) {
      assertThat(ContractSchemas.schema(PARTNER_SCHEMA).validate(sent)).as(sent.toString()).isEmpty();
    }
  }

  @Test
  void aTopupThePartnerRefusesReleasesTheHold() {
    TxnRequest req = request("01711000999");

    List<ConsumerRecord<String, String>> got = statuses(req.txnId(), 6);

    assertThat(names(got))
        .containsExactly("RECEIVED", "VALIDATING", "HOLDING", "PARTNER_PENDING", "RELEASING", "FAILED");
    assertThat(json.readTree(got.get(5).value()).path("error").path("code").asString())
        .isEqualTo("MW-PTR-GP-TOPUP_REJECTED");
    assertThat(commandTypes(req.txnId())).containsExactly("ValidateTransaction", "HoldFunds", "ReleaseHold");
  }
}
