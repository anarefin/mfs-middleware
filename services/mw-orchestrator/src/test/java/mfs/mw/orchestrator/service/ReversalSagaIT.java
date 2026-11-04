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
 * REVERSAL (ticket 19, spec 7.2 REVERSING) through the service seams: a {@code REVERSAL} request on
 * {@code mw.txn.request.v1} moves the original COMPLETED transaction to REVERSING, the core's
 * TransactionReversed (under the reversal's own txnId) ends it REVERSED, a ReversalRejected or a
 * core that never confirms ends it in MANUAL_REVIEW. Output is read from the outbox.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.orchestrator.consumers.enabled=false",
      "mw.orchestrator.timer.enabled=false"
    })
@Import({TestInfrastructure.class, SagaServiceIT.TestClock.class})
class ReversalSagaIT {

  private static final String STATUS_SCHEMA = "events/mw-orchestrator/mw.txn.status.v1.schema.json";
  private static final String COMMAND_SCHEMA = "events/mw-orchestrator/core.command.v1.schema.json";
  private static final String AUDIT_SCHEMA = "events/mw-audit-dw/mw.audit.event.v1.schema.json";
  private static final String COMMAND_TOPIC = "core.wallet.command.v1";

  private final TransactionService transactions;
  private final CoreEventService coreEvents;
  private final SagaTimerService timers;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  ReversalSagaIT(
      TransactionService transactions,
      CoreEventService coreEvents,
      SagaTimerService timers,
      Clock clock,
      JdbcTemplate jdbc,
      JsonMapper json) {
    this.transactions = transactions;
    this.coreEvents = coreEvents;
    this.timers = timers;
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

  // --- helpers -------------------------------------------------------------------------------

  private List<JsonNode> outbox(String topic, String key) {
    return jdbc.queryForList(
            "select payload::text from outbox where topic = ? and msg_key = ? order by id", String.class, topic, key)
        .stream()
        .map(json::readTree)
        .toList();
  }

  private List<String> statuses(String txnId) {
    return outbox("mw.txn.status.v1", txnId).stream().map(n -> n.path("status").asString()).toList();
  }

  private JsonNode lastStatus(String txnId) {
    List<JsonNode> all = outbox("mw.txn.status.v1", txnId);
    return all.get(all.size() - 1);
  }

  /** Every command on the wallet command topic, whatever its key. */
  private List<JsonNode> allCommands() {
    return jdbc.queryForList("select payload::text from outbox where topic = ? order by id", String.class, COMMAND_TOPIC)
        .stream()
        .map(json::readTree)
        .toList();
  }

  private List<JsonNode> reverseCommands() {
    return allCommands().stream().filter(c -> "ReverseTransaction".equals(c.path("commandType").asString())).toList();
  }

  /** "from>to" per audit record, "-" when there is no before. */
  private List<String> auditedMoves(String txnId) {
    return outbox("mw.audit.event.v1", txnId).stream()
        .map(a -> (a.has("before") ? a.path("before").path("status").asString() : "-")
            + ">" + a.path("after").path("status").asString())
        .toList();
  }

  private void assertAllValid(String... keys) {
    for (String key : keys) {
      for (JsonNode n : outbox("mw.txn.status.v1", key)) {
        assertThat(ContractSchemas.schema(STATUS_SCHEMA).validate(n)).as(n.toString()).isEmpty();
      }
      for (JsonNode n : outbox("mw.audit.event.v1", key)) {
        assertThat(ContractSchemas.schema(AUDIT_SCHEMA).validate(n)).as(n.toString()).isEmpty();
      }
    }
    for (JsonNode n : allCommands()) {
      assertThat(ContractSchemas.schema(COMMAND_SCHEMA).validate(n)).as(n.toString()).isEmpty();
    }
  }

  private String status(String txnId) {
    return jdbc.queryForObject("select status from transaction where txn_id = ?::uuid", String.class, txnId);
  }

  private CoreEvent event(String type, String txnId, String commandId, String reason) {
    String outcome = reason == null ? "SUCCESS" : "FAILURE";
    String reasonJson = reason == null ? "" : ",\"reasonCode\":\"" + reason + "\"";
    String payload = "TransactionValidated".equals(type) ? "{\"fee\":{\"valueMinor\":500,\"currency\":\"BDT\"}}" : "{}";
    return json.readValue(
        "{\"schemaVersion\":1,\"eventType\":\"" + type + "\",\"eventId\":\"evt-" + commandId
            + "\",\"commandId\":\"" + commandId + "\",\"txnId\":\"" + txnId
            + "\",\"coreRef\":\"CORE-9\",\"occurredAt\":\"2026-11-03T08:15:31Z\",\"outcome\":\""
            + outcome + "\"" + reasonJson + ",\"payload\":" + payload + "}",
        CoreEvent.class);
  }

  /** A P2P of {@code amount} that the core validated and posted. */
  private String completedP2p(long amount) {
    TxnRequest req = TestData.p2p(TestData.txnId(), "key-" + TestData.txnId(), amount);
    transactions.accept(req, "gw-1");
    String txn = req.txnId();
    coreEvents.handle(event("TransactionValidated", txn, txn + ":validate", null));
    coreEvents.handle(event("TransferPosted", txn, txn + ":post", null));
    assertThat(status(txn)).isEqualTo("COMPLETED");
    return txn;
  }

  /** Sends a REVERSAL of {@code original}; returns the reversal's own txnId. */
  private String reverse(String original, long amount, String idemKey) {
    TxnRequest req = TestData.reversal(TestData.txnId(), idemKey, original, amount);
    transactions.accept(req, "ops-1");
    return req.txnId();
  }

  // --- the reversal path ---------------------------------------------------------------------

  @Test
  void aReversalOfACompletedP2pSendsReverseTransactionAndEndsReversed() {
    String original = completedP2p(5_000);
    String reversal = reverse(original, 5_000, "rev-" + original);

    assertThat(status(original)).isEqualTo("REVERSING");
    assertThat(statuses(original)).endsWith("COMPLETED", "REVERSING");
    assertThat(lastStatus(original).path("attributes").path("reversalTxnId").asString()).isEqualTo(reversal);
    List<JsonNode> sent = reverseCommands();
    assertThat(sent).hasSize(1);
    JsonNode cmd = sent.get(0);
    assertThat(cmd.path("txnId").asString()).isEqualTo(reversal);
    assertThat(cmd.path("commandId").asString()).isEqualTo(reversal + ":reverse");
    assertThat(cmd.path("payload").path("originalTxnId").asString()).isEqualTo(original);
    assertThat(cmd.path("payload").path("amount").path("valueMinor").asLong()).isEqualTo(5_000);
    assertThat(cmd.path("payload").path("fee").path("valueMinor").asLong()).isEqualTo(500);

    coreEvents.handle(event("TransactionReversed", reversal, reversal + ":reverse", null));

    assertThat(status(original)).isEqualTo("REVERSED");
    assertThat(statuses(original)).endsWith("COMPLETED", "REVERSING", "REVERSED");
    assertThat(auditedMoves(original)).endsWith("COMPLETED>REVERSING", "REVERSING>REVERSED");
    assertThat(jdbc.queryForObject("select count(*) from saga_timer", Integer.class)).isZero();
    assertAllValid(original, reversal);
  }

  @Test
  void aCoreReversalRejectedEndsInManualReviewWithTheCoreReasonAudited() {
    String original = completedP2p(5_000);
    String reversal = reverse(original, 5_000, "rev-" + original);

    coreEvents.handle(event("ReversalRejected", reversal, reversal + ":reverse", "INSUFFICIENT_BALANCE"));

    assertThat(status(original)).isEqualTo("MANUAL_REVIEW");
    JsonNode last = lastStatus(original);
    assertThat(last.path("error").path("code").asString()).isEqualTo("MW-CORE-INSUFFICIENT_BALANCE");
    List<JsonNode> audits = outbox("mw.audit.event.v1", original);
    JsonNode audit = audits.get(audits.size() - 1);
    assertThat(audit.path("before").path("status").asString()).isEqualTo("REVERSING");
    assertThat(audit.path("after").path("status").asString()).isEqualTo("MANUAL_REVIEW");
    assertThat(audit.path("after").path("errorCode").asString()).isEqualTo("MW-CORE-INSUFFICIENT_BALANCE");
    assertThat(jdbc.queryForObject("select count(*) from saga_timer", Integer.class)).isZero();
    assertAllValid(original, reversal);
  }

  @Test
  void reversingResendsTheSameCommandThenGivesUpToManualReview() {
    String original = completedP2p(5_000);
    String reversal = reverse(original, 5_000, "rev-" + original);

    clock.advance(java.time.Duration.ofSeconds(5));
    timers.sweep();
    assertThat(status(original)).isEqualTo("REVERSING");
    assertThat(reverseCommands()).hasSize(2)
        .allSatisfy(c -> assertThat(c.path("commandId").asString()).isEqualTo(reversal + ":reverse"));

    clock.advance(java.time.Duration.ofSeconds(60));
    timers.sweep();
    assertThat(status(original)).isEqualTo("MANUAL_REVIEW");
    assertThat(lastStatus(original).path("error").path("code").asString()).isEqualTo("MW-TMO-020");
    assertThat(reverseCommands()).hasSize(2);
    assertThat(auditedMoves(original)).endsWith("COMPLETED>REVERSING", "REVERSING>MANUAL_REVIEW");
    assertAllValid(original, reversal);
  }

  @Test
  void aLateTransactionReversedAfterManualReviewChangesNothing() {
    String original = completedP2p(5_000);
    String reversal = reverse(original, 5_000, "rev-" + original);
    coreEvents.handle(event("ReversalRejected", reversal, reversal + ":reverse", "INSUFFICIENT_BALANCE"));

    coreEvents.handle(event("TransactionReversed", reversal, reversal + ":reverse-late", null));

    assertThat(status(original)).isEqualTo("MANUAL_REVIEW");
  }

  // --- refusals ------------------------------------------------------------------------------

  @Test
  void reversingATransactionThatIsNotCompletedIsRefused() {
    TxnRequest p2p = TestData.p2p(TestData.txnId(), "key-" + TestData.txnId(), 5_000);
    transactions.accept(p2p, "gw-1"); // VALIDATING
    String original = p2p.txnId();

    String reversal = reverse(original, 5_000, "rev-" + original);

    assertThat(status(original)).isEqualTo("VALIDATING");
    assertThat(statuses(reversal)).containsExactly("REJECTED");
    JsonNode refused = lastStatus(reversal);
    assertThat(refused.path("error").path("code").asString()).isEqualTo("MW-VAL-021");
    assertThat(refused.path("originalTxnId").asString()).isEqualTo(original);
    assertThat(refused.path("txnType").asString()).isEqualTo("REVERSAL");
    assertThat(reverseCommands()).isEmpty();
    assertThat(jdbc.queryForObject("select count(*) from transaction where txn_id = ?::uuid", Integer.class, reversal)).isZero();
    assertAllValid(original, reversal);
  }

  @Test
  void aRejectedOrFailedTransactionCannotBeReversed() {
    TxnRequest p2p = TestData.p2p(TestData.txnId(), "key-" + TestData.txnId(), 5_000);
    transactions.accept(p2p, "gw-1");
    String original = p2p.txnId();
    coreEvents.handle(event("TransactionRejected", original, original + ":validate", "INVALID_PIN"));

    String reversal = reverse(original, 5_000, "rev-" + original);

    assertThat(lastStatus(reversal).path("error").path("code").asString()).isEqualTo("MW-VAL-021");
    assertThat(status(original)).isEqualTo("REJECTED");
  }

  @Test
  void reversingAnUnknownTransactionIsRefused() {
    String reversal = reverse(TestData.txnId(), 5_000, "rev-unknown");

    assertThat(statuses(reversal)).containsExactly("REJECTED");
    assertThat(lastStatus(reversal).path("error").path("code").asString()).isEqualTo("MW-VAL-020");
    assertThat(reverseCommands()).isEmpty();
    assertAllValid(reversal);
  }

  @Test
  void aReversalOfAnotherAmountIsRefused() {
    String original = completedP2p(5_000);

    String reversal = reverse(original, 4_000, "rev-" + original);

    assertThat(lastStatus(reversal).path("error").path("code").asString()).isEqualTo("MW-VAL-022");
    assertThat(status(original)).isEqualTo("COMPLETED");
    assertThat(reverseCommands()).isEmpty();
  }

  @Test
  void aReversalFromACustomerChannelIsRefused() {
    String original = completedP2p(5_000);
    TxnRequest base = TestData.reversal(TestData.txnId(), "rev-" + original, original, 5_000);
    TxnRequest fromApp =
        new TxnRequest(
            1, base.txnId(), base.idempotencyKey(), base.correlationId(), "CUSTOMER_APP", "REVERSAL",
            base.initiator(), base.debitParty(), base.creditParty(), base.amount(), null, original, null,
            "en", null, null, TestData.NOW);

    transactions.accept(fromApp, "gw-1");

    assertThat(lastStatus(fromApp.txnId()).path("error").path("code").asString()).isEqualTo("MW-AUTH-020");
    assertThat(status(original)).isEqualTo("COMPLETED");
    assertThat(reverseCommands()).isEmpty();
  }

  // --- once only -----------------------------------------------------------------------------

  @Test
  void aSecondReversalOfTheSameTransactionIsIdempotent() {
    String original = completedP2p(5_000);
    String first = reverse(original, 5_000, "rev-1-" + original);
    int statusesBefore = statuses(original).size();

    String second = reverse(original, 5_000, "rev-2-" + original);

    assertThat(status(original)).isEqualTo("REVERSING");
    assertThat(reverseCommands()).hasSize(1);
    List<String> after = statuses(original);
    assertThat(after).hasSize(statusesBefore + 1);
    JsonNode answer = lastStatus(original);
    assertThat(answer.path("status").asString()).isEqualTo("REVERSING");
    assertThat(answer.path("attributes").path("duplicate").asString()).isEqualTo("true");
    assertThat(answer.path("attributes").path("requestTxnId").asString()).isEqualTo(second);
    assertThat(answer.path("attributes").path("reversalTxnId").asString()).isEqualTo(first);
    assertThat(auditedMoves(original)).containsOnlyOnce("COMPLETED>REVERSING");

    coreEvents.handle(event("TransactionReversed", first, first + ":reverse", null));
    String third = reverse(original, 5_000, "rev-3-" + original);
    assertThat(status(original)).isEqualTo("REVERSED");
    assertThat(lastStatus(original).path("attributes").path("requestTxnId").asString()).isEqualTo(third);
    assertThat(reverseCommands()).hasSize(1);
    assertAllValid(original);
  }

  @Test
  void theSameReversalRequestRepeatedWithItsKeyIsAnsweredFromTheStore() {
    String original = completedP2p(5_000);
    reverse(original, 5_000, "rev-" + original);

    String repeat = reverse(original, 5_000, "rev-" + original);
    assertThat(lastStatus(original).path("attributes").path("requestTxnId").asString()).isEqualTo(repeat);

    String conflicting = reverse(original, 4_000, "rev-" + original);
    assertThat(lastStatus(conflicting).path("error").path("code").asString()).isEqualTo("MW-IDEM-001");
    assertThat(reverseCommands()).hasSize(1);
  }
}
