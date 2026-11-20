package mfs.mw.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import mfs.mw.orchestrator.CoreCalls;
import mfs.mw.orchestrator.MutableClock;
import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.TestInfrastructure;
import mfs.mw.orchestrator.contracts.ContractSchemas;
import mfs.mw.orchestrator.dto.CoreEvent;
import mfs.mw.orchestrator.dto.PartnerReply;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.mapper.PartnerRequestMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reversal of a completed top-up or bill payment (ticket 96c, FRS PLT-022, PLT-025). The partner
 * REVERSE goes out first, on the same {@code reverse:<txnId>} key as a refused commit. The core
 * ReverseTransaction is sent only after the partner confirms, and a definite partner refusal ends
 * MANUAL_REVIEW without moving the posting. A flow with no held partner leg (P2P) stays core-only.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.orchestrator.consumers.enabled=false",
      "mw.orchestrator.timer.enabled=false"
    })
@Import({TestInfrastructure.class, CoreCalls.class, SagaServiceIT.TestClock.class})
class CompletedPartnerReversalIT {

  private static final String PARTNER_SCHEMA = "events/mw-orchestrator/mw.partner.request.v1.schema.json";

  private final TransactionService transactions;
  private final CoreCalls coreEvents;
  private final PartnerReplyService partnerReplies;
  private final SagaTimerService timers;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  CompletedPartnerReversalIT(
      TransactionService transactions,
      CoreCalls coreEvents,
      PartnerReplyService partnerReplies,
      SagaTimerService timers,
      Clock clock,
      JdbcTemplate jdbc,
      JsonMapper json) {
    this.transactions = transactions;
    this.coreEvents = coreEvents;
    this.partnerReplies = partnerReplies;
    this.timers = timers;
    this.clock = (MutableClock) clock;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeEach
  void clean() {
    clock.set(TestData.NOW);
    coreEvents.clear();
    for (String t : List.of(
        "outbox", "inbox", "saga_timer", "saga_step", "idempotency", "partner_call", "core_call", "wallet_txn",
        "transaction")) {
      jdbc.update("delete from " + t);
    }
  }

  @Test
  void aCompletedTopupReversesThePartnerBeforeTheCore() {
    String txn = completedTopup();
    String reversal = reverse(txn, 2_000);

    assertThat(status(txn)).isEqualTo("REVERSING");
    assertThat(pendingLeg(txn)).isEqualTo("PARTNER_REVERSE");
    List<JsonNode> partner = reverses("mw.partner.request.telco.v1", txn);
    assertThat(partner).hasSize(1);
    assertThat(partner.get(0).path("requestId").asString()).isEqualTo(reverseKey(txn));
    assertThat(partner.get(0).path("payload").path("operation").asString()).isEqualTo("REVERSE");
    assertThat(partner.get(0).path("payload").path("originalPartnerRef").asString()).isEqualTo("OCS-1");
    assertThat(partner.get(0).path("payload").path("amount").path("valueMinor").asLong()).isEqualTo(2_000);
    assertThat(ContractSchemas.schema(PARTNER_SCHEMA).validate(partner.get(0))).isEmpty();
    assertThat(reverseCommands()).isEmpty();

    partnerReplies.handle(reverseReply("telco", "gp", txn, "SUCCESS", null));

    assertThat(status(txn)).isEqualTo("REVERSING");
    assertThat(pendingLeg(txn)).isNull();
    List<JsonNode> core = reverseCommands();
    assertThat(core).hasSize(1);
    assertThat(core.get(0).path("txnId").asString()).isEqualTo(reversal);
    assertThat(core.get(0).path("commandId").asString()).isEqualTo(reversal + ":reverse");
    assertThat(core.get(0).path("payload").path("originalTxnId").asString()).isEqualTo(txn);
    assertThat(core.get(0).path("payload").path("amount").path("valueMinor").asLong()).isEqualTo(2_000);
    assertThat(commandTypes()).doesNotContain("ReleaseHold", "QueryTransaction");

    partnerReplies.handle(reverseReply("telco", "gp", txn, "SUCCESS", null));
    assertThat(reverseCommands()).hasSize(1);

    coreEvents.answer(event("TransactionReversed", reversal, reversal + ":reverse", null, "{}"));

    assertThat(status(txn)).isEqualTo("REVERSED");
    assertThat(pendingLeg(txn)).isNull();
    assertThat(statuses(txn)).endsWith("COMPLETED", "REVERSING", "REVERSED");
  }

  @Test
  void aPartnerRefusalOfACompletedTopupEndsManualReviewAndDoesNotTouchTheCore() {
    String txn = completedTopup();
    reverse(txn, 2_000);

    partnerReplies.handle(reverseReply("telco", "gp", txn, "FAILED", "MW-PTR-GP-REVERSE_REFUSED"));

    assertThat(status(txn)).isEqualTo("MANUAL_REVIEW");
    assertThat(pendingLeg(txn)).isNull();
    assertThat(reverseCommands()).isEmpty();
    assertThat(lastStatus(txn).path("error").path("code").asString()).isEqualTo("MW-PTR-GP-REVERSE_REFUSED");
  }

  @Test
  void noPartnerAnswerOfACompletedTopupGoesToManualReviewWithoutACoreReversal() {
    String txn = completedTopup();
    reverse(txn, 2_000);
    for (int i = 0; i < 12; i++) {
      clock.advance(Duration.ofSeconds(5));
      timers.sweep();
    }

    assertThat(status(txn)).isEqualTo("MANUAL_REVIEW");
    assertThat(pendingLeg(txn)).isNull();
    assertThat(lastStatus(txn).path("error").path("code").asString()).isEqualTo("MW-TMO-022");
    assertThat(reverseCommands()).isEmpty();
    assertThat(reverses("mw.partner.request.telco.v1", txn))
        .isNotEmpty()
        .allSatisfy(r -> assertThat(r.path("requestId").asString()).isEqualTo(reverseKey(txn)));
  }

  @Test
  void aCompletedBillPaymentReversesTheAggregatorBeforeTheCore() {
    String txn = completedBillPay();
    String reversal = reverse(txn, 66_700);

    assertThat(pendingLeg(txn)).isEqualTo("PARTNER_REVERSE");
    List<JsonNode> partner = reverses("mw.partner.request.billpay.v1", txn);
    assertThat(partner).hasSize(1);
    assertThat(partner.get(0).path("family").asString()).isEqualTo("billpay");
    assertThat(partner.get(0).path("payload").path("operation").asString()).isEqualTo("REVERSE");
    assertThat(partner.get(0).path("payload").path("originalPartnerRef").asString()).isEqualTo("AGG-7");
    assertThat(reverses("mw.partner.request.telco.v1", txn)).isEmpty();
    assertThat(reverseCommands()).isEmpty();

    partnerReplies.handle(reverseReply("billpay", "billagg", txn, "SUCCESS", null));

    assertThat(pendingLeg(txn)).isNull();
    assertThat(reverseCommands()).hasSize(1);
    assertThat(reverseCommands().get(0).path("txnId").asString()).isEqualTo(reversal);
    assertThat(reverseCommands().get(0).path("payload").path("originalTxnId").asString()).isEqualTo(txn);

    coreEvents.answer(event("TransactionReversed", reversal, reversal + ":reverse", null, "{}"));
    assertThat(status(txn)).isEqualTo("REVERSED");
  }

  @Test
  void aPartnerRefusalOfACompletedBillPaymentEndsManualReview() {
    String txn = completedBillPay();
    reverse(txn, 66_700);

    partnerReplies.handle(reverseReply("billpay", "billagg", txn, "FAILED", "MW-PTR-BILLAGG-REVERSE_REFUSED"));

    assertThat(status(txn)).isEqualTo("MANUAL_REVIEW");
    assertThat(reverseCommands()).isEmpty();
    assertThat(lastStatus(txn).path("error").path("code").asString()).isEqualTo("MW-PTR-BILLAGG-REVERSE_REFUSED");
  }

  private static String reverseKey(String txnId) {
    return UUID.nameUUIDFromBytes(("reverse:" + txnId).getBytes(StandardCharsets.UTF_8)).toString();
  }

  private String completedTopup() {
    TxnRequest req = TestData.topup(TestData.txnId(), "key-" + TestData.txnId(), 2_000, "01711000002");
    transactions.accept(req, "gw-1");
    String txn = req.txnId();
    coreEvents.answer(event("TransactionValidated", txn, txn + ":validate", null,
        "{\"fee\":{\"valueMinor\":200,\"currency\":\"BDT\"}}"));
    coreEvents.answer(event("FundsHeld", txn, txn + ":hold", null, "{\"holdId\":\"HOLD-CORE-7\"}"));
    partnerReplies.handle(payReply("telco", "gp", txn, "OCS-1"));
    coreEvents.answer(event("HoldCommitted", txn, txn + ":commit", null, "{\"holdId\":\"HOLD-CORE-7\"}"));
    assertThat(status(txn)).isEqualTo("COMPLETED");
    return txn;
  }

  private String completedBillPay() {
    TxnRequest req = TestData.billPay(TestData.txnId(), "key-" + TestData.txnId(), 66_700, "1234567");
    transactions.accept(req, "gw-1");
    String txn = req.txnId();
    coreEvents.answer(event("TransactionValidated", txn, txn + ":validate", null,
        "{\"fee\":{\"valueMinor\":300,\"currency\":\"BDT\"}}"));
    coreEvents.answer(event("FundsHeld", txn, txn + ":hold", null, "{\"holdId\":\"HOLD-CORE-9\"}"));
    partnerReplies.handle(payReply("billpay", "billagg", txn, "AGG-7"));
    coreEvents.answer(event("HoldCommitted", txn, txn + ":commit", null, "{\"holdId\":\"HOLD-CORE-9\"}"));
    assertThat(status(txn)).isEqualTo("COMPLETED");
    return txn;
  }

  private String reverse(String original, long amount) {
    TxnRequest req = TestData.reversal(TestData.txnId(), "rev-" + original, original, amount);
    transactions.accept(req, "ops-1");
    return req.txnId();
  }

  private PartnerReply payReply(String family, String partnerId, String txnId, String partnerRef) {
    return json.readValue(
        "{\"schemaVersion\":1,\"family\":\"" + family + "\",\"requestId\":\""
            + PartnerRequestMapper.requestId(txnId, "PAY") + "\",\"txnId\":\"" + txnId
            + "\",\"correlationId\":\"corr-1\",\"partnerId\":\"" + partnerId
            + "\",\"repliedAt\":\"2026-11-03T08:15:33Z\",\"payload\":{\"operation\":\"PAY\",\"outcome\":\"SUCCESS\","
            + "\"partnerRef\":\"" + partnerRef + "\"}}",
        PartnerReply.class);
  }

  private PartnerReply reverseReply(String family, String partnerId, String txnId, String outcome, String errorCode) {
    String payload = "{\"operation\":\"REVERSE\",\"outcome\":\"" + outcome + "\""
        + ("SUCCESS".equals(outcome) ? ",\"partnerRef\":\"REV-1\"" : "")
        + (errorCode == null ? "" : ",\"errorCode\":\"" + errorCode + "\",\"message\":\"Reverse refused\",\"retryable\":false")
        + "}";
    return json.readValue(
        "{\"schemaVersion\":1,\"family\":\"" + family + "\",\"requestId\":\"" + reverseKey(txnId) + "\",\"txnId\":\""
            + txnId + "\",\"correlationId\":\"corr-1\",\"partnerId\":\"" + partnerId
            + "\",\"repliedAt\":\"2026-11-03T08:15:40Z\",\"payload\":" + payload + "}",
        PartnerReply.class);
  }

  private CoreEvent event(String type, String txnId, String commandId, String reason, String payload) {
    String outcome = reason == null ? "SUCCESS" : "FAILURE";
    String reasonJson = reason == null ? "" : ",\"reasonCode\":\"" + reason + "\"";
    return json.readValue(
        "{\"schemaVersion\":1,\"eventType\":\"" + type + "\",\"eventId\":\"evt-" + type + "-" + commandId
            + "\",\"commandId\":\"" + commandId + "\",\"txnId\":\"" + txnId
            + "\",\"coreRef\":\"CORE-7\",\"occurredAt\":\"2026-11-03T08:15:31Z\",\"outcome\":\""
            + outcome + "\"" + reasonJson + ",\"payload\":" + payload + "}",
        CoreEvent.class);
  }

  private String status(String txnId) {
    return jdbc.queryForObject("select status from transaction where txn_id = ?::uuid", String.class, txnId);
  }

  private String pendingLeg(String txnId) {
    return jdbc.queryForObject("select pending_leg from transaction where txn_id = ?::uuid", String.class, txnId);
  }

  private JsonNode lastStatus(String txnId) {
    List<String> rows = jdbc.queryForList(
        "select payload::text from outbox where topic = 'mw.txn.status.v1' and msg_key = ? order by id",
        String.class, txnId);
    return json.readTree(rows.get(rows.size() - 1));
  }

  private List<String> statuses(String txnId) {
    return jdbc.queryForList(
            "select payload::text from outbox where topic = 'mw.txn.status.v1' and msg_key = ? order by id",
            String.class, txnId)
        .stream()
        .map(json::readTree)
        .map(n -> n.path("status").asString())
        .toList();
  }

  private List<JsonNode> reverses(String topic, String txnId) {
    return jdbc.queryForList(
            "select payload::text from outbox where topic = ? and msg_key = ? order by id",
            String.class, topic, txnId)
        .stream()
        .map(json::readTree)
        .filter(n -> "REVERSE".equals(n.path("payload").path("operation").asString()))
        .toList();
  }

  private List<JsonNode> reverseCommands() {
    return coreEvents.commands().stream()
        .filter(c -> "ReverseTransaction".equals(c.path("commandType").asString()))
        .toList();
  }

  private List<String> commandTypes() {
    return coreEvents.commands().stream().map(c -> c.path("commandType").asString()).toList();
  }
}
