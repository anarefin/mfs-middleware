package mfs.mw.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import mfs.mw.orchestrator.MutableClock;
import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.TestInfrastructure;
import mfs.mw.orchestrator.contracts.ContractSchemas;
import mfs.mw.orchestrator.dto.CoreEvent;
import mfs.mw.orchestrator.dto.TxnRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A child of an org bulk disbursement (ticket 34, spec 5.2 BULK_DISBURSE, 9.4): the org's wallet
 * pays one customer, validate + post like P2P, authorised by the portal's maker-checker instead of
 * a PIN. Only the org portal channel may send one; a resent child (same txnId and key, after a
 * portal crash) never becomes a second transaction.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"mw.orchestrator.consumers.enabled=false", "mw.orchestrator.timer.enabled=false"})
@Import({TestInfrastructure.class, SagaServiceIT.TestClock.class})
class BulkDisburseSagaIT {

  private static final String STATUS_SCHEMA = "events/mw-orchestrator/mw.txn.status.v1.schema.json";
  private static final String COMMAND_SCHEMA = "events/mw-orchestrator/core.command.v1.schema.json";

  private final TransactionService transactions;
  private final CoreEventService coreEvents;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  BulkDisburseSagaIT(
      TransactionService transactions, CoreEventService coreEvents, Clock clock, JdbcTemplate jdbc, JsonMapper json) {
    this.transactions = transactions;
    this.coreEvents = coreEvents;
    this.clock = (MutableClock) clock;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeEach
  void clean() {
    clock.set(TestData.NOW);
    for (String t : List.of("outbox", "inbox", "saga_timer", "saga_step", "idempotency", "transaction")) {
      jdbc.update("delete from " + t);
    }
  }

  private List<JsonNode> outbox(String topic, String txnId) {
    return jdbc.queryForList(
            "select payload::text from outbox where topic = ? and msg_key = ? order by id", String.class, topic, txnId)
        .stream()
        .map(json::readTree)
        .toList();
  }

  private List<String> statuses(String txnId) {
    return outbox("mw.txn.status.v1", txnId).stream().map(n -> n.path("status").asString()).toList();
  }

  private List<JsonNode> commands(String txnId) {
    return outbox("core.wallet.command.v1", txnId);
  }

  private CoreEvent event(String type, String txnId, String step) {
    return json.readValue(
        "{\"schemaVersion\":1,\"eventType\":\"" + type + "\",\"eventId\":\"evt-" + type + "-" + txnId
            + "\",\"commandId\":\"" + txnId + ":" + step + "\",\"txnId\":\"" + txnId
            + "\",\"coreRef\":\"CORE-1\",\"occurredAt\":\"2026-11-03T08:15:31Z\",\"outcome\":\"SUCCESS\",\"payload\":{}}",
        CoreEvent.class);
  }

  private void assertAllValid(String txnId) {
    for (JsonNode n : outbox("mw.txn.status.v1", txnId)) {
      assertThat(ContractSchemas.schema(STATUS_SCHEMA).validate(n)).as(n.toString()).isEmpty();
    }
    for (JsonNode n : commands(txnId)) {
      assertThat(ContractSchemas.schema(COMMAND_SCHEMA).validate(n)).as(n.toString()).isEmpty();
    }
  }

  @Test
  void aChildFromTheOrgPortalIsValidatedAndPostedWithoutAPin() {
    TxnRequest child = TestData.bulkChild(TestData.txnId(), "bulk-b1-2", 10_000, "01711000005");
    transactions.accept(child, null);
    String txn = child.txnId();
    assertThat(statuses(txn)).containsExactly("RECEIVED", "VALIDATING");
    JsonNode validate = commands(txn).get(0);
    assertThat(validate.path("commandType").asString()).isEqualTo("ValidateTransaction");
    assertThat(validate.path("payload").has("encryptedPin")).isFalse();
    assertThat(validate.path("payload").path("txnType").asString()).isEqualTo("BULK_DISBURSE");
    assertThat(validate.path("payload").path("debitParty").path("id").asString()).isEqualTo("01311000001");

    coreEvents.handle(event("TransactionValidated", txn, "validate"));
    assertThat(commands(txn).get(1).path("commandType").asString()).isEqualTo("PostTransfer");
    coreEvents.handle(event("TransferPosted", txn, "post"));

    assertThat(statuses(txn)).containsExactly("RECEIVED", "VALIDATING", "POSTING", "COMPLETED");
    List<JsonNode> all = outbox("mw.txn.status.v1", txn);
    assertThat(all.get(all.size() - 1).path("attributes").path("parentTxnId").asString())
        .isEqualTo("01933b8e-0000-7000-8000-000000000001");
    assertAllValid(txn);
  }

  @Test
  void aResentChildIsAnsweredWithTheStoredTransactionAndPaysOnce() {
    TxnRequest child = TestData.bulkChild(TestData.txnId(), "bulk-b1-3", 10_000, "01711000006");
    transactions.accept(child, null);
    transactions.accept(child, null); // the portal crashed before stamping the outbox row sent

    assertThat(jdbc.queryForObject("select count(*) from transaction", Long.class)).isEqualTo(1);
    assertThat(commands(child.txnId())).hasSize(1);
    assertAllValid(child.txnId());
  }

  @Test
  void anotherChannelMayNotSendABulkDisbursement() {
    TxnRequest c = TestData.bulkChild(TestData.txnId(), "bulk-x", 10_000, "01711000007");
    TxnRequest fromApp =
        new TxnRequest(1, c.txnId(), c.idempotencyKey(), c.correlationId(), "CUSTOMER_APP", c.txnType(),
            c.initiator(), c.debitParty(), c.creditParty(), c.amount(), null, null, null, null, null,
            c.attributes(), c.requestedAt());
    transactions.accept(fromApp, null);

    assertThat(statuses(c.txnId())).containsExactly("RECEIVED", "REJECTED");
    List<JsonNode> all = outbox("mw.txn.status.v1", c.txnId());
    assertThat(all.get(1).path("error").path("code").asString()).isEqualTo("MW-AUTH-021");
    assertThat(commands(c.txnId())).isEmpty();
    assertAllValid(c.txnId());
  }
}
