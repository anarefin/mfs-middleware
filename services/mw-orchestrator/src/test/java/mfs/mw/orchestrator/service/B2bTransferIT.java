package mfs.mw.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import mfs.mw.orchestrator.CoreCalls;
import mfs.mw.orchestrator.MutableClock;
import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.TestInfrastructure;
import mfs.mw.orchestrator.contracts.ContractSchemas;
import mfs.mw.orchestrator.dto.Amount;
import mfs.mw.orchestrator.dto.CoreEvent;
import mfs.mw.orchestrator.dto.Party;
import mfs.mw.orchestrator.dto.TxnRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * B2B float (ticket 106, UC-07): a distributor wallet pays an agent. Validate, then PostTransfer.
 * An insufficient float is rejected at validation, before any post. A customer token never reaches
 * this service: the gateway answers 403 ({@code RoleScopingTest}).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.orchestrator.consumers.enabled=false",
      "mw.orchestrator.timer.enabled=false"
    })
@Import({TestInfrastructure.class, CoreCalls.class, B2bTransferIT.TestClock.class})
class B2bTransferIT {

  @TestConfiguration(proxyBeanMethods = false)
  static class TestClock {
    @Bean
    @org.springframework.context.annotation.Primary
    MutableClock testClock() {
      return new MutableClock(TestData.NOW);
    }
  }

  private static final String STATUS_SCHEMA = "events/mw-orchestrator/mw.txn.status.v1.schema.json";
  private static final String AUDIT_SCHEMA = "events/mw-audit-dw/mw.audit.event.v1.schema.json";

  private final TransactionService transactions;
  private final CoreCalls coreEvents;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  B2bTransferIT(TransactionService transactions, CoreCalls coreEvents, Clock clock, JdbcTemplate jdbc, JsonMapper json) {
    this.transactions = transactions;
    this.coreEvents = coreEvents;
    this.jdbc = jdbc;
    this.json = json;
    ((MutableClock) clock).set(TestData.NOW);
  }

  @BeforeEach
  void clean() {
    for (String t : List.of("outbox", "inbox", "saga_timer", "saga_step", "idempotency", "transaction")) {
      jdbc.update("delete from " + t);
    }
  }

  @Test
  void aDistributorToAgentTransferCompletes() {
    String txn = start();
    assertThat(commands(txn)).hasSize(1);
    assertThat(commands(txn).get(0).path("commandType").asString()).isEqualTo("ValidateTransaction");

    coreEvents.answer(event("TransactionValidated", txn, "validate", null, "{}"));
    assertThat(commands(txn)).hasSize(2);
    assertThat(commands(txn).get(1).path("commandType").asString()).isEqualTo("PostTransfer");

    coreEvents.answer(event("TransferPosted", txn, "post", null, "{}"));
    assertThat(status(txn)).isEqualTo("COMPLETED");
    assertAllValid(txn);
  }

  @Test
  void insufficientFloatIsRejectedBeforePosting() {
    String txn = start();
    coreEvents.answer(event("TransactionRejected", txn, "validate", "INSUFFICIENT_BALANCE", "{}"));

    assertThat(status(txn)).isEqualTo("REJECTED");
    assertThat(lastStatus(txn).path("error").path("code").asString()).isEqualTo("MW-CORE-INSUFFICIENT_BALANCE");
    assertThat(commands(txn)).hasSize(1);
    assertThat(commands(txn).get(0).path("commandType").asString()).isEqualTo("ValidateTransaction");
    assertAllValid(txn);
  }

  private String start() {
    String txnId = TestData.txnId();
    TxnRequest req = new TxnRequest(
        1, txnId, "key-" + txnId, "corr-" + txnId.substring(0, 8), "DISTRIBUTOR_APP", "B2B_TRANSFER",
        new Party("MSISDN", "01612000001", null, null),
        new Party("WALLET", "01612000001", null, "DISTRIBUTOR"),
        new Party("WALLET", "01812000001", null, "AGENT"),
        Amount.bdt(1_000), null, null, TestData.ASSERTION, "en",
        Map.of("deviceId", "dev-b2b-1"), null, TestData.NOW);
    transactions.accept(req, "gw-1");
    return txnId;
  }

  private List<JsonNode> outbox(String topic, String txnId) {
    return jdbc.queryForList(
            "select payload::text from outbox where topic = ? and msg_key = ? order by id", String.class, topic, txnId)
        .stream().map(json::readTree).toList();
  }

  private List<JsonNode> commands(String txnId) {
    return coreEvents.commands(txnId);
  }

  private String status(String txnId) {
    return jdbc.queryForObject("select status from transaction where txn_id = ?::uuid", String.class, txnId);
  }

  private JsonNode lastStatus(String txnId) {
    List<JsonNode> all = outbox("mw.txn.status.v1", txnId);
    return all.get(all.size() - 1);
  }

  private void assertAllValid(String txnId) {
    for (JsonNode n : outbox("mw.txn.status.v1", txnId)) {
      assertThat(ContractSchemas.schema(STATUS_SCHEMA).validate(n)).as(n.toString()).isEmpty();
    }
    for (JsonNode n : outbox("mw.audit.event.v1", txnId)) {
      assertThat(ContractSchemas.schema(AUDIT_SCHEMA).validate(n)).as(n.toString()).isEmpty();
    }
  }

  private CoreEvent event(String type, String txnId, String step, String reason, String payload) {
    String outcome = reason == null ? "SUCCESS" : "FAILURE";
    String reasonJson = reason == null ? "" : ",\"reasonCode\":\"" + reason + "\"";
    return json.readValue(
        "{\"schemaVersion\":1,\"eventType\":\"" + type + "\",\"eventId\":\"evt-" + type + "-" + txnId
            + "\",\"commandId\":\"" + txnId + ":" + step + "\",\"txnId\":\"" + txnId
            + "\",\"coreRef\":\"CORE-1\",\"occurredAt\":\"2026-11-03T08:15:31Z\",\"outcome\":\""
            + outcome + "\"" + reasonJson + ",\"payload\":" + payload + "}",
        CoreEvent.class);
  }
}
