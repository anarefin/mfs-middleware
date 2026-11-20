package mfs.mw.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import mfs.mw.orchestrator.MutableClock;
import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.CoreCalls;
import mfs.mw.orchestrator.TestInfrastructure;
import mfs.mw.orchestrator.contracts.ContractSchemas;
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
 * BALANCE (ADR D26) through the service seams against real PostgreSQL: one BalanceInquiry, the
 * core's answer passed through on the COMPLETED status and never stored (not on the transaction
 * row, the saga timeline, the core call log, the audit trail or the wallet history); a rejection
 * or no answer FAILS it; the intake refuses a malformed check; a balance check is never reversed.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"mw.orchestrator.consumers.enabled=false", "mw.orchestrator.timer.enabled=false"})
@Import({TestInfrastructure.class, CoreCalls.class, BalanceSagaIT.TestClock.class})
class BalanceSagaIT {

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
  private static final String WALLET = "01711000001";
  /** The reported balance; looked for everywhere the middleware stores something. */
  private static final long AVAILABLE = 1_234_567;

  private final TransactionService transactions;
  private final CoreCalls coreEvents;
  private final SagaTimerService timers;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  BalanceSagaIT(
      TransactionService transactions,
      CoreCalls coreEvents,
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
    for (String t : List.of("outbox", "inbox", "saga_timer", "saga_step", "core_call", "idempotency", "transaction")) {
      jdbc.update("delete from " + t);
    }
  }

  // --- helpers -------------------------------------------------------------------------------

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

  private JsonNode lastStatus(String txnId) {
    List<JsonNode> all = outbox("mw.txn.status.v1", txnId);
    return all.get(all.size() - 1);
  }

  /** Commands of both core topics. */
  private List<JsonNode> commands(String txnId) {
    return coreEvents.commands(txnId);
  }

  private void assertAllValid(String txnId) {
    for (JsonNode n : outbox("mw.txn.status.v1", txnId)) {
      assertThat(ContractSchemas.schema(STATUS_SCHEMA).validate(n)).as(n.toString()).isEmpty();
    }
    for (JsonNode n : outbox("mw.audit.event.v1", txnId)) {
      assertThat(ContractSchemas.schema(AUDIT_SCHEMA).validate(n)).as(n.toString()).isEmpty();
    }
  }

  private CoreEvent event(String type, String txnId, String reason, String payload) {
    String outcome = reason == null ? "SUCCESS" : "FAILURE";
    String reasonJson = reason == null ? "" : ",\"reasonCode\":\"" + reason + "\"";
    return json.readValue(
        "{\"schemaVersion\":1,\"eventType\":\"" + type + "\",\"eventId\":\"evt-" + type + "-" + txnId
            + "\",\"commandId\":\"" + txnId + ":balance\",\"txnId\":\"" + txnId
            + "\",\"coreRef\":\"CORE-1\",\"occurredAt\":\"2026-11-03T08:15:31Z\",\"outcome\":\""
            + outcome + "\"" + reasonJson + ",\"payload\":" + payload + "}",
        CoreEvent.class);
  }

  private CoreEvent reported(String txnId) {
    return event("BalanceReported", txnId, null,
        "{\"walletId\":\"" + WALLET + "\",\"available\":{\"valueMinor\":" + AVAILABLE
            + ",\"currency\":\"BDT\"},\"asOf\":\"2026-11-03T08:15:31Z\"}");
  }

  private String start() {
    TxnRequest req = TestData.balance(TestData.txnId(), "key-" + TestData.txnId(), WALLET);
    transactions.accept(req, "gw-1");
    return req.txnId();
  }

  private String status(String txnId) {
    return jdbc.queryForObject("select status from transaction where txn_id = ?::uuid", String.class, txnId);
  }

  /** Every row the middleware keeps about {@code txnId}, as text (the outbox's status rows aside). */
  private String storedTrace(String txnId) {
    StringBuilder all = new StringBuilder();
    for (String sql : List.of(
        "select row_to_json(t)::text from transaction t where txn_id = ?::uuid",
        "select row_to_json(s)::text from saga_step s where txn_id = ?::uuid",
        "select row_to_json(c)::text from core_call c where txn_id = ?::uuid",
        "select row_to_json(w)::text from wallet_txn w where txn_id = ?::uuid")) {
      jdbc.queryForList(sql, String.class, txnId).forEach(all::append);
    }
    jdbc.queryForList("select row_to_json(i)::text from inbox i", String.class).forEach(all::append);
    outbox("mw.audit.event.v1", txnId).forEach(a -> all.append(a.toString()));
    return all.toString();
  }

  // --- the inquiry -----------------------------------------------------------------------------

  @Test
  void aBalanceCheckSendsOneBalanceInquiryAndCompletesWithTheCoresAnswer() {
    String txn = start();

    assertThat(statuses(txn)).containsExactly("RECEIVED", "VALIDATING");
    assertThat(commands(txn)).singleElement().satisfies(c -> {
      assertThat(c.path("commandType").asString()).isEqualTo("BalanceInquiry");
      assertThat(c.path("commandId").asString()).isEqualTo(txn + ":balance");
    });
    assertThat(coreEvents.commands(txn)).hasSize(1);

    coreEvents.answer(reported(txn));

    assertThat(statuses(txn)).containsExactly("RECEIVED", "VALIDATING", "COMPLETED");
    JsonNode done = lastStatus(txn);
    assertThat(done.path("previousStatus").asString()).isEqualTo("VALIDATING");
    assertThat(done.path("balance").path("available").path("valueMinor").asLong()).isEqualTo(AVAILABLE);
    assertThat(done.path("balance").path("asOf").asString()).isEqualTo("2026-11-03T08:15:31Z");
    assertThat(done.path("coreRef").asString()).isEqualTo("CORE-1");
    assertThat(outbox("mw.txn.status.v1", txn).subList(0, 2)).noneMatch(s -> s.has("balance"));
    assertThat(commands(txn)).hasSize(1);
    assertThat(jdbc.queryForObject("select count(*) from saga_timer", Long.class)).isZero();
    assertAllValid(txn);
  }

  @Test
  void theBalanceIsNeverStored() {
    String txn = start();
    coreEvents.answer(reported(txn));
    assertThat(status(txn)).isEqualTo("COMPLETED");

    String trace = storedTrace(txn);
    assertThat(trace).isNotBlank().doesNotContain(String.valueOf(AVAILABLE)).doesNotContain("available")
        .doesNotContain("\"balance\"").doesNotContain("asOf");
    assertThat(outbox("mw.audit.event.v1", txn))
        .extracting(a -> a.path("after").path("status").asString())
        .containsExactly("RECEIVED", "VALIDATING", "COMPLETED");
    assertThat(jdbc.queryForObject("select count(*) from wallet_txn where txn_id = ?::uuid", Long.class, txn))
        .as("no entry in the wallet history (ADR D25)").isZero();
  }

  @Test
  void aRepeatedBalanceCheckIsAnsweredWithoutTheBalanceItNeverKept() {
    TxnRequest req = TestData.balance(TestData.txnId(), "key-rep", WALLET);
    transactions.accept(req, null);
    coreEvents.answer(reported(req.txnId()));
    TxnRequest again = TestData.balance(TestData.txnId(), "key-rep", WALLET);

    transactions.accept(again, null);

    JsonNode answer = lastStatus(req.txnId());
    assertThat(answer.path("status").asString()).isEqualTo("COMPLETED");
    assertThat(answer.path("attributes").path("requestTxnId").asString()).isEqualTo(again.txnId());
    assertThat(answer.has("balance")).isFalse();
    assertThat(commands(req.txnId())).hasSize(1);
  }

  @Test
  void aCoreRejectionFailsWithTheMappedError() {
    String txn = start();
    coreEvents.answer(event("BalanceInquiryRejected", txn, "WALLET_NOT_FOUND", "{}"));

    assertThat(statuses(txn)).containsExactly("RECEIVED", "VALIDATING", "FAILED");
    JsonNode failed = lastStatus(txn);
    assertThat(failed.path("error").path("code").asString()).isEqualTo("MW-CORE-WALLET_NOT_FOUND");
    assertThat(failed.path("error").path("retryable").asBoolean()).isFalse();
    assertThat(failed.has("balance")).isFalse();
    assertAllValid(txn);
  }

  @Test
  void aRefusedAssertionPassesThroughAsItIs() {
    String txn = start();
    coreEvents.answer(event("BalanceInquiryRejected", txn, "MW-AUTH-046", "{}"));

    assertThat(status(txn)).isEqualTo("FAILED");
    assertThat(lastStatus(txn).path("error").path("code").asString()).isEqualTo("MW-AUTH-046");
    assertAllValid(txn);
  }

  @Test
  void noAnswerFailsAfterTheValidatingDeadlineWithoutAnInquiry() {
    String txn = start();
    clock.advance(Duration.ofSeconds(4));
    assertThat(timers.sweep()).isZero();
    clock.advance(Duration.ofSeconds(2));

    assertThat(timers.sweep()).isEqualTo(1);

    assertThat(statuses(txn)).containsExactly("RECEIVED", "VALIDATING", "FAILED");
    JsonNode failed = lastStatus(txn);
    assertThat(failed.path("error").path("code").asString()).isEqualTo("MW-TMO-001");
    assertThat(failed.path("error").path("retryable").asBoolean()).isTrue();
    assertThat(commands(txn)).as("no status inquiry").hasSize(1);
    assertThat(jdbc.queryForObject("select count(*) from saga_timer", Long.class)).isZero();
    assertAllValid(txn);

    // a late answer changes nothing
    coreEvents.answer(reported(txn));
    assertThat(status(txn)).isEqualTo("FAILED");
    assertThat(statuses(txn)).hasSize(3);
  }

  @Test
  void answersOfOtherCommandsAreIgnored() {
    String txn = start();
    coreEvents.answer(json.readValue(
        "{\"schemaVersion\":1,\"eventType\":\"TransactionValidated\",\"eventId\":\"evt-v-" + txn
            + "\",\"commandId\":\"" + txn + ":validate\",\"txnId\":\"" + txn
            + "\",\"occurredAt\":\"2026-11-03T08:15:31Z\",\"outcome\":\"SUCCESS\",\"payload\":{}}",
        CoreEvent.class));

    assertThat(status(txn)).isEqualTo("VALIDATING");
    assertThat(commands(txn)).hasSize(1);
  }

  @Test
  void aBalanceAnswerForAMoneyTransactionIsIgnored() {
    TxnRequest p2p = TestData.p2p(TestData.txnId(), "key-p2p", 5_000);
    transactions.accept(p2p, null);

    coreEvents.answer(reported(p2p.txnId()));

    assertThat(status(p2p.txnId())).isEqualTo("VALIDATING");
  }

  // --- refusals --------------------------------------------------------------------------------

  private void assertRefused(TxnRequest req, String code) {
    transactions.accept(req, null);
    assertThat(statuses(req.txnId())).as(req.toString()).containsExactly("RECEIVED", "REJECTED");
    assertThat(lastStatus(req.txnId()).path("error").path("code").asString()).isEqualTo(code);
    assertThat(commands(req.txnId())).isEmpty();
    assertAllValid(req.txnId());
  }

  @Test
  void aBalanceCheckWithAnAmountIsRefused() {
    TxnRequest b = TestData.balance(TestData.txnId(), "key-amt", WALLET);
    assertRefused(TestData.with(b, b.debitParty(), b.creditParty(), 100), "MW-VAL-011");
  }

  @Test
  void aBalanceCheckOfAnotherWalletOrANonWalletIsRefused() {
    TxnRequest other = TestData.balance(TestData.txnId(), "key-other", WALLET);
    assertRefused(
        TestData.with(other, other.debitParty(), new Party("WALLET", "01711000002", null, "CUSTOMER"), 0),
        "MW-VAL-001");
    TxnRequest org = TestData.balance(TestData.txnId(), "key-org", WALLET);
    Party orgWallet = new Party("ORG", "01311000001", null, null);
    assertRefused(TestData.with(org, orgWallet, orgWallet, 0), "MW-VAL-001");
  }

  @Test
  void theSameWalletInAnotherFormIsTheSameWallet() {
    TxnRequest b = TestData.balance(TestData.txnId(), "key-form", WALLET);
    transactions.accept(
        TestData.with(b, b.debitParty(), new Party("WALLET", "+8801711000001", null, "CUSTOMER"), 0), null);
    assertThat(status(b.txnId())).isEqualTo("VALIDATING");
  }

  @Test
  void aBalanceCheckWithoutAnAssertionIsRefused() {
    TxnRequest b = TestData.balance(TestData.txnId(), "key-noauth", WALLET);
    assertRefused(
        new TxnRequest(b.schemaVersion(), b.txnId(), b.idempotencyKey(), b.correlationId(), b.channel(), b.txnType(),
            b.initiator(), b.debitParty(), b.creditParty(), b.amount(), null, null, null, b.locale(), null, null,
            b.requestedAt()),
        "MW-VAL-001");
  }

  @Test
  void aZeroAmountIsStillRefusedForMoney() {
    assertRefused(TestData.p2p(TestData.txnId(), "key-zero", 0), "MW-VAL-011");
  }

  @Test
  void aCompletedBalanceCheckCannotBeReversed() {
    String txn = start();
    coreEvents.answer(reported(txn));
    TxnRequest rev = TestData.reversal(TestData.txnId(), "key-rev", txn, 0);

    transactions.accept(rev, null);

    assertThat(lastStatus(rev.txnId()).path("error").path("code").asString()).isEqualTo("MW-VAL-021");
    assertThat(status(txn)).isEqualTo("COMPLETED");
    assertThat(commands(rev.txnId())).isEmpty();
    assertThat(commands(txn)).hasSize(1);
  }
}
