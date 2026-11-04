package mfs.mw.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The saga through its service seams (intake, core events, timer sweep) against real PostgreSQL.
 * Output is read where the service hands it over: the transactional outbox.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.orchestrator.consumers.enabled=false",
      "mw.orchestrator.timer.enabled=false"
    })
@Import({TestInfrastructure.class, SagaServiceIT.TestClock.class})
class SagaServiceIT {

  @TestConfiguration(proxyBeanMethods = false)
  static class TestClock {
    @Bean
    @org.springframework.context.annotation.Primary
    MutableClock testClock() {
      return new MutableClock(TestData.NOW);
    }
  }

  private static final String STATUS_SCHEMA = "events/mw-orchestrator/mw.txn.status.v1.schema.json";
  private static final String COMMAND_SCHEMA = "events/mw-orchestrator/core.command.v1.schema.json";
  private static final String AUDIT_SCHEMA = "events/mw-audit-dw/mw.audit.event.v1.schema.json";

  private final TransactionService transactions;
  private final CoreEventService coreEvents;
  private final SagaTimerService timers;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  SagaServiceIT(
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

  private List<JsonNode> outbox(String topic, String txnId) {
    return jdbc.queryForList(
            "select payload::text from outbox where topic = ? and msg_key = ? order by id",
            String.class,
            topic,
            txnId)
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

  private List<JsonNode> audits(String txnId) {
    return outbox("mw.audit.event.v1", txnId);
  }

  /** "from>to" per audit record, "-" when there is no before. */
  private List<String> auditedMoves(String txnId) {
    return audits(txnId).stream()
        .map(a -> (a.has("before") ? a.path("before").path("status").asString() : "-")
            + ">" + a.path("after").path("status").asString())
        .toList();
  }

  private JsonNode lastStatus(String txnId) {
    List<JsonNode> all = outbox("mw.txn.status.v1", txnId);
    return all.get(all.size() - 1);
  }

  private void assertAllValid(String txnId) {
    for (JsonNode n : outbox("mw.txn.status.v1", txnId)) {
      assertThat(ContractSchemas.schema(STATUS_SCHEMA).validate(n)).as(n.toString()).isEmpty();
    }
    for (JsonNode n : commands(txnId)) {
      assertThat(ContractSchemas.schema(COMMAND_SCHEMA).validate(n)).as(n.toString()).isEmpty();
    }
    for (JsonNode n : audits(txnId)) {
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

  private String start(long amount) {
    TxnRequest req = TestData.p2p(TestData.txnId(), "key-" + TestData.txnId(), amount);
    transactions.accept(req, "gw-1");
    return req.txnId();
  }

  private String status(String txnId) {
    return jdbc.queryForObject("select status from transaction where txn_id = ?::uuid", String.class, txnId);
  }

  // --- happy path and rejections -------------------------------------------------------------

  @Test
  void p2pRunsReceivedValidatingPostingCompleted() {
    String txn = start(5_000);
    assertThat(statuses(txn)).containsExactly("RECEIVED", "VALIDATING");
    assertThat(commands(txn)).hasSize(1);
    assertThat(commands(txn).get(0).path("commandType").asString()).isEqualTo("ValidateTransaction");
    assertThat(commands(txn).get(0).path("commandId").asString()).isEqualTo(txn + ":validate");

    coreEvents.handle(
        event("TransactionValidated", txn, "validate", null, "{\"fee\":{\"valueMinor\":500,\"currency\":\"BDT\"}}"));
    assertThat(statuses(txn)).containsExactly("RECEIVED", "VALIDATING", "POSTING");
    assertThat(commands(txn).get(1).path("commandType").asString()).isEqualTo("PostTransfer");
    assertThat(commands(txn).get(1).path("commandId").asString()).isEqualTo(txn + ":post");
    assertThat(commands(txn).get(1).path("payload").path("encryptedPin").path("ciphertext").asString())
        .isEqualTo("ZmFrZS1jaXBoZXJ0ZXh0");

    coreEvents.handle(event("TransferPosted", txn, "post", null, "{}"));
    assertThat(statuses(txn)).containsExactly("RECEIVED", "VALIDATING", "POSTING", "COMPLETED");
    JsonNode done = lastStatus(txn);
    assertThat(done.path("previousStatus").asString()).isEqualTo("POSTING");
    assertThat(done.path("fee").path("valueMinor").asLong()).isEqualTo(500);
    assertThat(done.path("coreRef").asString()).isEqualTo("CORE-1");

    assertThat(jdbc.queryForObject("select encrypted_pin is null from transaction where txn_id = ?::uuid", Boolean.class, txn)).isTrue();
    assertThat(jdbc.queryForObject("select count(*) from saga_timer", Long.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from saga_step where txn_id = ?::uuid", Long.class, txn)).isEqualTo(3);
    assertAllValid(txn);
  }

  // --- audit trail (ticket 12, spec 14.3) ---------------------------------------------------------

  @Test
  void everyStateAP2pPassesThroughIsAuditedOnce() {
    String txn = start(5_000);
    coreEvents.handle(event("TransactionValidated", txn, "validate", null, "{}"));
    coreEvents.handle(event("TransferPosted", txn, "post", null, "{}"));

    assertThat(auditedMoves(txn))
        .containsExactly("->RECEIVED", "RECEIVED>VALIDATING", "VALIDATING>POSTING", "POSTING>COMPLETED");
    List<JsonNode> audits = audits(txn);
    assertThat(audits).extracting(a -> a.path("auditId").asString()).doesNotHaveDuplicates();
    for (JsonNode a : audits) {
      assertThat(a.path("action").asString()).isEqualTo("txn.state.changed");
      assertThat(a.path("resource").asString()).isEqualTo("txn/" + txn);
      assertThat(a.path("txnId").asString()).isEqualTo(txn);
      assertThat(a.path("actor").path("type").asString()).isEqualTo("system");
      assertThat(a.path("actor").path("id").asString()).isEqualTo("mw-orchestrator");
      assertThat(a.path("service").asString()).isEqualTo("mw-orchestrator");
      assertThat(a.path("outcome").asString()).isEqualTo("SUCCESS");
      // no parties, amounts or PIN: nothing personal to mask
      assertThat(a.toString()).doesNotContain("encryptedPin", "debitParty", "creditParty", "amount");
    }
    assertAllValid(txn);
  }

  @Test
  void anAuditRecordIsWrittenWithItsStateChangeAndCarriesTheError() {
    String txn = start(5_000);
    coreEvents.handle(event("TransactionRejected", txn, "validate", "INSUFFICIENT_BALANCE", "{}"));

    assertThat(auditedMoves(txn)).containsExactly("->RECEIVED", "RECEIVED>VALIDATING", "VALIDATING>REJECTED");
    JsonNode rejected = audits(txn).get(2);
    assertThat(rejected.path("after").path("errorCode").asString()).isEqualTo("MW-CORE-INSUFFICIENT_BALANCE");
    assertThat(jdbc.queryForObject("select count(*) from saga_step where txn_id = ?::uuid", Long.class, txn))
        .isEqualTo(audits(txn).size() - 1L);
    assertAllValid(txn);
  }

  @Test
  void aTimeoutIsAuditedAndAnIgnoredEventIsNot() {
    String txn = start(5_000);
    clock.advance(Duration.ofSeconds(6));
    timers.sweep();
    coreEvents.handle(event("TransactionValidated", txn, "validate", null, "{}")); // late, refused

    assertThat(auditedMoves(txn)).containsExactly("->RECEIVED", "RECEIVED>VALIDATING", "VALIDATING>FAILED");
    assertAllValid(txn);
  }

  @Test
  void aRepeatedRequestChangesNoStateAndIsNotAudited() {
    TxnRequest first = TestData.p2p(TestData.txnId(), "key-repeat-audit", 5_000);
    transactions.accept(first, "gw-1");
    TxnRequest again = TestData.p2p(TestData.txnId(), "key-repeat-audit", 5_000);
    transactions.accept(again, "gw-1");

    assertThat(audits(first.txnId())).hasSize(2);
    assertThat(audits(again.txnId())).isEmpty();
  }

  @Test
  void statusRecordsCarryTheReplyToHeader() {
    String txn = start(5_000);
    List<String> headers =
        jdbc.queryForList(
            "select headers->>'mw-reply-to' from outbox where topic = 'mw.txn.status.v1' and msg_key = ?", String.class, txn);
    assertThat(headers).containsOnly("gw-1");
  }

  @Test
  void aCoreRejectionAtValidationEndsRejectedWithTheMappedError() {
    String txn = start(5_000);
    coreEvents.handle(event("TransactionRejected", txn, "validate", "INSUFFICIENT_BALANCE", "{}"));

    assertThat(statuses(txn)).containsExactly("RECEIVED", "VALIDATING", "REJECTED");
    JsonNode rejected = lastStatus(txn);
    assertThat(rejected.path("error").path("code").asString()).isEqualTo("MW-CORE-INSUFFICIENT_BALANCE");
    assertThat(rejected.path("error").path("retryable").asBoolean()).isFalse();
    assertThat(commands(txn)).hasSize(1);
    assertAllValid(txn);
  }

  @Test
  void aCoreRejectionAtPostingEndsRejected() {
    String txn = start(5_000);
    coreEvents.handle(event("TransactionValidated", txn, "validate", null, "{}"));
    coreEvents.handle(event("TransferRejected", txn, "post", "LIMIT_EXCEEDED_DAILY", "{}"));

    assertThat(status(txn)).isEqualTo("REJECTED");
    assertThat(lastStatus(txn).path("error").path("code").asString()).isEqualTo("MW-CORE-LIMIT_EXCEEDED_DAILY");
    assertThat(jdbc.queryForObject("select encrypted_pin is null from transaction where txn_id = ?::uuid", Boolean.class, txn)).isTrue();
    assertAllValid(txn);
  }

  @Test
  void aTypeWithoutAFlowIsRejectedAtOnce() {
    TxnRequest p2p = TestData.p2p(TestData.txnId(), "k-topup", 5_000);
    TxnRequest topup =
        new TxnRequest(1, p2p.txnId(), "k-topup", p2p.correlationId(), p2p.channel(), "ATM_CASH_IN",
            p2p.initiator(), p2p.debitParty(), p2p.creditParty(), p2p.amount(), null, null,
            p2p.encryptedPin(), null, null, null, p2p.requestedAt());
    transactions.accept(topup, null);

    assertThat(statuses(p2p.txnId())).containsExactly("RECEIVED", "REJECTED");
    assertThat(lastStatus(p2p.txnId()).path("error").path("code").asString()).isEqualTo("MW-VAL-010");
    assertThat(commands(p2p.txnId())).isEmpty();
    assertAllValid(p2p.txnId());
  }

  @Test
  void aZeroAmountIsRejectedAtOnce() {
    String txn = start(0);
    assertThat(statuses(txn)).containsExactly("RECEIVED", "REJECTED");
    assertThat(lastStatus(txn).path("error").path("code").asString()).isEqualTo("MW-VAL-011");
    assertThat(commands(txn)).isEmpty();
  }

  // --- stale and duplicate core events ---------------------------------------------------------

  @Test
  void aRedeliveredCoreEventIsAppliedOnce() {
    String txn = start(5_000);
    CoreEvent validated = event("TransactionValidated", txn, "validate", null, "{}");
    coreEvents.handle(validated);
    coreEvents.handle(validated);

    assertThat(statuses(txn)).containsExactly("RECEIVED", "VALIDATING", "POSTING");
    assertThat(commands(txn)).hasSize(2);
  }

  @Test
  void anEventThatDoesNotFitTheCurrentStateIsIgnored() {
    String txn = start(5_000);
    coreEvents.handle(event("TransferPosted", txn, "post", null, "{}"));

    assertThat(status(txn)).isEqualTo("VALIDATING");
    assertThat(statuses(txn)).containsExactly("RECEIVED", "VALIDATING");
  }

  @Test
  void anEventForAnUnknownTransactionOrTypeIsIgnored() {
    coreEvents.handle(event("TransferPosted", TestData.txnId(), "post", null, "{}"));
    String txn = start(5_000);
    coreEvents.handle(event("SomethingNew", txn, "validate", null, "{}"));
    assertThat(status(txn)).isEqualTo("VALIDATING");
  }

  // --- idempotency ---------------------------------------------------------------------------

  @Test
  void sameKeyAndSameRequestReturnsTheStoredTxnIdAndCurrentStatus() {
    TxnRequest first = TestData.p2p(TestData.txnId(), "key-same", 5_000);
    transactions.accept(first, "gw-1");
    TxnRequest again = TestData.p2p(TestData.txnId(), "key-same", 5_000);
    transactions.accept(again, "gw-2");

    assertThat(jdbc.queryForObject("select count(*) from transaction", Long.class)).isEqualTo(1);
    assertThat(commands(first.txnId())).hasSize(1);
    assertThat(outbox("mw.txn.status.v1", again.txnId())).isEmpty();
    JsonNode answer = lastStatus(first.txnId());
    assertThat(answer.path("txnId").asString()).isEqualTo(first.txnId());
    assertThat(answer.path("status").asString()).isEqualTo("VALIDATING");
    assertThat(answer.path("attributes").path("requestTxnId").asString()).isEqualTo(again.txnId());
    assertThat(
            jdbc.queryForObject(
                "select headers->>'mw-reply-to' from outbox where topic = 'mw.txn.status.v1' and msg_key = ? order by id desc limit 1",
                String.class, first.txnId()))
        .isEqualTo("gw-2");
    assertAllValid(first.txnId());
  }

  @Test
  void sameKeyWithDifferentContentIsMwIdem001() {
    TxnRequest first = TestData.p2p(TestData.txnId(), "key-same", 5_000);
    transactions.accept(first, "gw-1");
    TxnRequest other = TestData.p2p(TestData.txnId(), "key-same", 9_000);
    transactions.accept(other, "gw-1");

    assertThat(jdbc.queryForObject("select count(*) from transaction", Long.class)).isEqualTo(1);
    assertThat(statuses(other.txnId())).containsExactly("REJECTED");
    JsonNode refused = lastStatus(other.txnId());
    assertThat(refused.path("error").path("code").asString()).isEqualTo("MW-IDEM-001");
    assertThat(commands(other.txnId())).isEmpty();
    assertAllValid(other.txnId());
  }

  @Test
  void anExpiredKeyIsReusable() {
    TxnRequest first = TestData.p2p(TestData.txnId(), "key-old", 5_000);
    transactions.accept(first, null);
    clock.advance(Duration.ofHours(73));
    TxnRequest later = TestData.p2p(TestData.txnId(), "key-old", 9_000);
    transactions.accept(later, null);

    assertThat(jdbc.queryForObject("select count(*) from transaction", Long.class)).isEqualTo(2);
    assertThat(statuses(later.txnId())).containsExactly("RECEIVED", "VALIDATING");
  }

  // --- timers --------------------------------------------------------------------------------

  @Test
  void nothingHappensBeforeTheDeadline() {
    String txn = start(5_000);
    clock.advance(Duration.ofSeconds(4));
    assertThat(timers.sweep()).isZero();
    assertThat(status(txn)).isEqualTo("VALIDATING");
  }

  @Test
  void aMissingValidateReplyMovesValidatingToFailedAfterFiveSeconds() {
    String txn = start(5_000);
    clock.advance(Duration.ofSeconds(6));

    assertThat(timers.sweep()).isEqualTo(1);

    assertThat(statuses(txn)).containsExactly("RECEIVED", "VALIDATING", "FAILED");
    JsonNode failed = lastStatus(txn);
    assertThat(failed.path("error").path("code").asString()).isEqualTo("MW-TMO-001");
    assertThat(failed.path("error").path("retryable").asBoolean()).isTrue();
    assertThat(jdbc.queryForObject("select count(*) from saga_timer", Long.class)).isZero();
    assertAllValid(txn);
    // the sweep is idempotent
    assertThat(timers.sweep()).isZero();
  }

  @Test
  void aMissingPostReplyMovesPostingToUnknownAfterFiveSeconds() {
    String txn = start(5_000);
    coreEvents.handle(event("TransactionValidated", txn, "validate", null, "{}"));
    clock.advance(Duration.ofSeconds(4));
    assertThat(timers.sweep()).isZero();
    clock.advance(Duration.ofSeconds(2));

    assertThat(timers.sweep()).isEqualTo(1);

    assertThat(status(txn)).isEqualTo("UNKNOWN");
    assertThat(lastStatus(txn).path("previousStatus").asString()).isEqualTo("POSTING");
    assertAllValid(txn);
  }

  @Test
  void aLateReplyAfterTheTimeoutIsIgnored() {
    String txn = start(5_000);
    clock.advance(Duration.ofSeconds(6));
    timers.sweep();
    coreEvents.handle(event("TransactionValidated", txn, "validate", null, "{}"));

    assertThat(status(txn)).isEqualTo("FAILED");
    assertThat(commands(txn)).hasSize(1);
  }

  @Test
  void aTimerForAFinishedTransactionDoesNothing() {
    String txn = start(5_000);
    jdbc.update("update transaction set status = 'COMPLETED' where txn_id = ?::uuid", txn);
    clock.advance(Duration.ofSeconds(6));
    assertThat(timers.sweep()).isEqualTo(1);
    assertThat(status(txn)).isEqualTo("COMPLETED");
    assertThat(jdbc.queryForObject("select count(*) from saga_timer", Long.class)).isZero();
  }
}
