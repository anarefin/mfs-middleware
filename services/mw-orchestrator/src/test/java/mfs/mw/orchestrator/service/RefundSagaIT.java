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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Merchant refund (ticket 116b, FRS CH-WEB-03, FL-06): a WEB_CHECKOUT {@code REVERSAL} of a completed
 * web checkout payment is its own transaction record (txnType REVERSAL, own txnId and idempotency
 * key), never a change of the original. Only the merchant that owns the payment may ask for it; the
 * amount (partial or whole) goes to the core's {@code POST /reversals}, which keeps the refunded
 * total and refuses an overshoot ({@code MW-CORE-REFUND_EXCEEDS}). Output is read from the outbox.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.orchestrator.consumers.enabled=false",
      "mw.orchestrator.timer.enabled=false"
    })
@Import({TestInfrastructure.class, CoreCalls.class, SagaServiceIT.TestClock.class})
class RefundSagaIT {

  private static final String STATUS_SCHEMA = "events/mw-orchestrator/mw.txn.status.v1.schema.json";
  private static final String AUDIT_SCHEMA = "events/mw-audit-dw/mw.audit.event.v1.schema.json";
  private static final String MERCHANT = "shop-42";
  private static final String MERCHANT_WALLET = "01811000042";

  private final TransactionService transactions;
  private final CoreCalls core;
  private final SagaTimerService timers;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  RefundSagaIT(
      TransactionService transactions, CoreCalls core, SagaTimerService timers, Clock clock,
      JdbcTemplate jdbc, JsonMapper json) {
    this.transactions = transactions;
    this.core = core;
    this.timers = timers;
    this.clock = (MutableClock) clock;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeEach
  void clean() {
    clock.set(TestData.NOW);
    core.clear();
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

  private String status(String txnId) {
    List<String> s = jdbc.queryForList("select status from transaction where txn_id = ?::uuid", String.class, txnId);
    return s.isEmpty() ? null : s.get(0);
  }

  private List<JsonNode> reverseCommands() {
    return core.commands().stream().filter(c -> "ReverseTransaction".equals(c.path("commandType").asString())).toList();
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
  }

  private CoreEvent event(String type, String txnId, String commandId, String reason) {
    String outcome = reason == null ? "SUCCESS" : "FAILURE";
    String reasonJson = reason == null ? "" : ",\"reasonCode\":\"" + reason + "\"";
    String payload = "TransactionValidated".equals(type)
        ? "{\"fee\":{\"valueMinor\":0,\"currency\":\"BDT\"}}"
        : "TransactionStatus".equals(type) ? "{\"txnStatus\":\"REVERSED\"}" : "{}";
    return json.readValue(
        "{\"schemaVersion\":1,\"eventType\":\"" + type + "\",\"eventId\":\"evt-" + commandId
            + "\",\"commandId\":\"" + commandId + "\",\"txnId\":\"" + txnId
            + "\",\"coreRef\":\"CORE-9\",\"occurredAt\":\"2026-11-03T08:15:31Z\",\"outcome\":\""
            + outcome + "\"" + reasonJson + ",\"payload\":" + payload + "}",
        CoreEvent.class);
  }

  /** A web checkout payment of {@code amount} to {@code merchant}'s wallet, validated and posted. */
  private String completedCheckout(long amount, String channel) {
    String txnId = TestData.txnId();
    TxnRequest base = TestData.merchantPay(txnId, "checkout:" + txnId, amount, MERCHANT_WALLET);
    TxnRequest req = new TxnRequest(
        1, base.txnId(), base.idempotencyKey(), base.correlationId(), channel, "MERCHANT_PAY",
        base.initiator(), base.debitParty(), base.creditParty(), base.amount(), null, null,
        base.assertion(), "en", Map.of("checkoutSessionId", "sess-" + txnId.substring(0, 8), "merchantId", MERCHANT),
        null, TestData.NOW);
    transactions.accept(req, "checkout-1");
    core.answer(event("TransactionValidated", txnId, txnId + ":validate", null));
    core.answer(event("TransferPosted", txnId, txnId + ":post", null));
    assertThat(status(txnId)).isEqualTo("COMPLETED");
    core.clear();
    return txnId;
  }

  private String completedCheckout(long amount) {
    return completedCheckout(amount, "WEB_CHECKOUT");
  }

  /** A merchant refund request as mw-checkout sends it. */
  private TxnRequest refundRequest(String original, String merchant, long amount, String idemKey) {
    String txnId = TestData.txnId();
    return new TxnRequest(
        1, txnId, idemKey, "corr-" + txnId.substring(0, 8), "WEB_CHECKOUT", "REVERSAL",
        new Party("MERCHANT", merchant, null, null),
        new Party("WALLET", MERCHANT_WALLET, null, "MERCHANT"),
        new Party("WALLET", "01711000001", null, "CUSTOMER"),
        Amount.bdt(amount), null, original, "en", Map.of("merchantId", merchant), null, TestData.NOW);
  }

  private String refund(String original, long amount, String idemKey) {
    TxnRequest req = refundRequest(original, MERCHANT, amount, idemKey);
    transactions.accept(req, "checkout-1");
    return req.txnId();
  }

  // --- the refund path -----------------------------------------------------------------------

  @Test
  void aPartialRefundIsItsOwnTransactionAndLeavesTheOriginalCompleted() {
    String original = completedCheckout(10_000);
    String refund = refund(original, 4_000, "refund-1");

    assertThat(status(original)).isEqualTo("COMPLETED");
    assertThat(status(refund)).isEqualTo("REVERSING");
    List<JsonNode> sent = reverseCommands();
    assertThat(sent).hasSize(1);
    JsonNode cmd = sent.get(0);
    assertThat(cmd.path("txnId").asString()).isEqualTo(refund);
    assertThat(cmd.path("commandId").asString()).isEqualTo(refund + ":reverse");
    assertThat(cmd.path("payload").path("originalTxnId").asString()).isEqualTo(original);
    assertThat(cmd.path("payload").path("refund").path("valueMinor").asLong()).isEqualTo(4_000);

    core.answer(event("TransactionReversed", refund, refund + ":reverse", null));

    assertThat(status(refund)).isEqualTo("COMPLETED");
    assertThat(statuses(refund)).containsExactly("RECEIVED", "REVERSING", "COMPLETED");
    JsonNode last = lastStatus(refund);
    assertThat(last.path("txnType").asString()).isEqualTo("REVERSAL");
    assertThat(last.path("originalTxnId").asString()).isEqualTo(original);
    assertThat(last.path("amount").path("valueMinor").asLong()).isEqualTo(4_000);
    assertThat(last.path("attributes").path("originalTxnId").asString()).isEqualTo(original);
    assertThat(last.path("attributes").path("refundOf").asString()).isEqualTo("MERCHANT_PAY");
    assertThat(status(original)).isEqualTo("COMPLETED");
    assertThat(statuses(original)).endsWith("COMPLETED");
    assertThat(jdbc.queryForObject("select count(*) from saga_timer", Integer.class)).isZero();
    assertAllValid(original, refund);
  }

  @Test
  void twoPartialRefundsAreTwoRecordsAndAnOvershootTheCoreRefusesEndsRejected() {
    String original = completedCheckout(10_000);
    String first = refund(original, 6_000, "refund-1");
    core.answer(event("TransactionReversed", first, first + ":reverse", null));
    String second = refund(original, 3_000, "refund-2");
    core.answer(event("TransactionReversed", second, second + ":reverse", null));
    String third = refund(original, 2_000, "refund-3");

    core.answer(event("ReversalRejected", third, third + ":reverse", "REFUND_EXCEEDS"));

    assertThat(status(first)).isEqualTo("COMPLETED");
    assertThat(status(second)).isEqualTo("COMPLETED");
    assertThat(status(third)).isEqualTo("REJECTED");
    assertThat(lastStatus(third).path("error").path("code").asString()).isEqualTo("MW-CORE-REFUND_EXCEEDS");
    assertThat(status(original)).isEqualTo("COMPLETED");
    assertThat(reverseCommands()).extracting(c -> c.path("payload").path("refund").path("valueMinor").asLong())
        .containsExactly(6_000L, 3_000L, 2_000L);
    assertAllValid(original, first, second, third);
  }

  @Test
  void theSameIdempotencyKeyTwiceGivesOneRefund() {
    String original = completedCheckout(10_000);
    String refund = refund(original, 4_000, "refund-1");
    TxnRequest again = refundRequest(original, MERCHANT, 4_000, "refund-1");
    transactions.accept(again, "checkout-1");

    assertThat(status(again.txnId())).isNull();
    assertThat(reverseCommands()).hasSize(1);
    JsonNode answer = lastStatus(refund);
    assertThat(answer.path("attributes").path("duplicate").asString()).isEqualTo("true");
    assertThat(answer.path("attributes").path("requestTxnId").asString()).isEqualTo(again.txnId());
    assertThat(jdbc.queryForObject("select count(*) from transaction where request->>'txnType' = 'REVERSAL'", Integer.class))
        .isEqualTo(1);
    assertThat(refund).isNotEqualTo(again.txnId());
  }

  @Test
  void anotherMerchantMayNotRefundThePayment() {
    String original = completedCheckout(10_000);
    TxnRequest req = refundRequest(original, "shop-99", 4_000, "refund-x");
    transactions.accept(req, "checkout-1");

    assertThat(status(req.txnId())).isNull();
    assertThat(reverseCommands()).isEmpty();
    JsonNode refused = lastStatus(req.txnId());
    assertThat(refused.path("status").asString()).isEqualTo("REJECTED");
    assertThat(refused.path("error").path("code").asString()).isEqualTo("MW-AUTH-022");
    assertThat(status(original)).isEqualTo("COMPLETED");
    assertAllValid(req.txnId());
  }

  @Test
  void aWebCheckoutRefundOfAPaymentMadeElsewhereIsRefused() {
    String original = completedCheckout(10_000, "CUSTOMER_APP");
    TxnRequest req = refundRequest(original, MERCHANT, 4_000, "refund-x");
    transactions.accept(req, "checkout-1");

    assertThat(status(req.txnId())).isNull();
    assertThat(lastStatus(req.txnId()).path("error").path("code").asString()).isEqualTo("MW-AUTH-022");
    assertThat(reverseCommands()).isEmpty();
  }

  @Test
  void aRefundAboveTheOriginalAmountIsRefusedBeforeTheCore() {
    String original = completedCheckout(10_000);
    TxnRequest req = refundRequest(original, MERCHANT, 10_001, "refund-x");
    transactions.accept(req, "checkout-1");

    assertThat(status(req.txnId())).isNull();
    assertThat(lastStatus(req.txnId()).path("error").path("code").asString()).isEqualTo("MW-VAL-022");
    assertThat(reverseCommands()).isEmpty();
  }

  @Test
  void aRefundTheCoreDoesNotAnswerIsResentThenConfirmedByAQuery() {
    String original = completedCheckout(10_000);
    String refund = refund(original, 4_000, "refund-1");

    clock.advance(java.time.Duration.ofSeconds(6));
    timers.sweep();
    assertThat(reverseCommands()).hasSize(2)
        .allSatisfy(c -> assertThat(c.path("commandId").asString()).isEqualTo(refund + ":reverse"));

    // resends stop at reversing-timeout, then the core is asked about the refund's own txnId
    clock.advance(java.time.Duration.ofSeconds(60));
    timers.sweep();
    clock.advance(java.time.Duration.ofSeconds(60));
    timers.sweep();
    JsonNode query = core.commands().stream()
        .filter(c -> c.path("commandId").asString().contains(":reverse-query:"))
        .findFirst().orElseThrow();
    assertThat(query.path("txnId").asString()).isEqualTo(refund);

    core.answer(event("TransactionStatus", refund, query.path("commandId").asString(), null));

    assertThat(status(refund)).isEqualTo("COMPLETED");
    assertThat(status(original)).isEqualTo("COMPLETED");
  }

  @Test
  void aSystemReversalOfARefundIsRefused() {
    String original = completedCheckout(10_000);
    String refund = refund(original, 4_000, "refund-1");
    core.answer(event("TransactionReversed", refund, refund + ":reverse", null));
    TxnRequest req = TestData.reversal(TestData.txnId(), "rev-" + refund, refund, 4_000);
    transactions.accept(req, "ops-1");

    assertThat(lastStatus(req.txnId()).path("error").path("code").asString()).isEqualTo("MW-VAL-021");
    assertThat(status(refund)).isEqualTo("COMPLETED");
  }
}
