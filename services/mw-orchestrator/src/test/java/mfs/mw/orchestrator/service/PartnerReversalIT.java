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
 * Partner compensation after a refused commit (ticket 96b, FRS PLT-022, PLT-025). A top-up the
 * partner already delivered and the core then refused to commit sends one REVERSE, keyed
 * {@code reverse:<txnId>}, and only after the partner confirms asks the core about the hold: a
 * hold that is still there is released and the transaction ends REVERSED; a hold that is already
 * gone ends REVERSED with nothing to release; an unclear answer goes to MANUAL_REVIEW. The pending
 * leg is the {@code pending_leg} column.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.orchestrator.consumers.enabled=false",
      "mw.orchestrator.timer.enabled=false"
    })
@Import({TestInfrastructure.class, CoreCalls.class, SagaServiceIT.TestClock.class})
class PartnerReversalIT {

  private static final String PARTNER_TOPIC = "mw.partner.request.telco.v1";
  private static final String PARTNER_SCHEMA = "events/mw-orchestrator/mw.partner.request.v1.schema.json";

  private final TransactionService transactions;
  private final CoreCalls coreEvents;
  private final PartnerReplyService partnerReplies;
  private final SagaTimerService timers;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  PartnerReversalIT(
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
  void aRefusedCommitReversesThePartnerThenReleasesAHoldThatStillExists() {
    String txn = committedTopupTheCoreRefuses();

    assertThat(status(txn)).isEqualTo("REVERSING");
    assertThat(pendingLeg(txn)).isEqualTo("PARTNER_REVERSE");
    List<JsonNode> reverses = reverses(txn);
    assertThat(reverses).hasSize(1);
    JsonNode reverse = reverses.get(0);
    assertThat(reverse.path("requestId").asString()).isEqualTo(reverseKey(txn));
    assertThat(reverse.path("payload").path("operation").asString()).isEqualTo("REVERSE");
    assertThat(reverse.path("payload").path("amount").path("valueMinor").asLong()).isEqualTo(2_000);
    assertThat(reverse.path("payload").path("originalPartnerRef").asString()).isEqualTo("OCS-1");
    assertThat(ContractSchemas.schema(PARTNER_SCHEMA).validate(reverse)).isEmpty();
    assertThat(commandTypes(txn)).doesNotContain("ReleaseHold", "ReverseTransaction");

    partnerReplies.handle(reverseReply(txn, "SUCCESS", null));

    assertThat(status(txn)).isEqualTo("REVERSING");
    assertThat(pendingLeg(txn)).isEqualTo("HOLD_QUERY");
    assertThat(commandTypes(txn)).contains("QueryTransaction");
    assertThat(lastCommand(txn).path("commandId").asString()).contains(":hold-check:");

    coreEvents.answer(status(txn, lastCommand(txn).path("commandId").asString(), "HELD"));

    assertThat(pendingLeg(txn)).isEqualTo("HOLD_RELEASE");
    assertThat(lastCommand(txn).path("commandType").asString()).isEqualTo("ReleaseHold");
    assertThat(lastCommand(txn).path("payload").path("holdId").asString()).isEqualTo("HOLD-CORE-7");
    assertThat(commandTypes(txn)).doesNotContain("ReverseTransaction");

    coreEvents.answer(event("HoldReleased", txn, txn + ":release", null, "{}"));

    assertThat(status(txn)).isEqualTo("REVERSED");
    assertThat(pendingLeg(txn)).isNull();
    assertThat(statuses(txn)).endsWith("COMMITTING", "REVERSING", "REVERSED");
  }

  @Test
  void aHoldThatIsAlreadyGoneEndsReversedWithNothingToRelease() {
    String txn = committedTopupTheCoreRefuses();
    partnerReplies.handle(reverseReply(txn, "SUCCESS", null));

    coreEvents.answer(status(txn, lastCommand(txn).path("commandId").asString(), "RELEASED"));

    assertThat(status(txn)).isEqualTo("REVERSED");
    assertThat(pendingLeg(txn)).isNull();
    assertThat(commandTypes(txn)).doesNotContain("ReleaseHold", "ReverseTransaction");
  }

  @Test
  void anUnclearCoreAnswerGoesToManualReview() {
    String txn = committedTopupTheCoreRefuses();
    partnerReplies.handle(reverseReply(txn, "SUCCESS", null));

    coreEvents.answer(status(txn, lastCommand(txn).path("commandId").asString(), "COMMITTED"));

    assertThat(status(txn)).isEqualTo("MANUAL_REVIEW");
    assertThat(pendingLeg(txn)).isNull();
    assertThat(commandTypes(txn)).doesNotContain("ReleaseHold");
    assertThat(lastStatus(txn).path("error").path("code").asString()).isEqualTo("MW-TMO-021");
  }

  @Test
  void aDuplicateReverseAckIsIgnored() {
    String txn = committedTopupTheCoreRefuses();
    PartnerReply ack = reverseReply(txn, "SUCCESS", null);
    partnerReplies.handle(ack);
    int queries = (int) commandTypes(txn).stream().filter("QueryTransaction"::equals).count();

    partnerReplies.handle(ack);

    assertThat(commandTypes(txn).stream().filter("QueryTransaction"::equals).count()).isEqualTo(queries);
    assertThat(pendingLeg(txn)).isEqualTo("HOLD_QUERY");
    assertThat(status(txn)).isEqualTo("REVERSING");
  }

  @Test
  void aReverseIsResentWithTheSameKeyUntilThePartnerConfirms() {
    String txn = committedTopupTheCoreRefuses();
    clock.advance(Duration.ofSeconds(6));
    assertThat(timers.sweep()).isEqualTo(1);

    List<JsonNode> reverses = reverses(txn);
    assertThat(reverses).hasSize(2);
    assertThat(reverses).allSatisfy(r -> assertThat(r.path("requestId").asString()).isEqualTo(reverseKey(txn)));
    assertThat(pendingLeg(txn)).isEqualTo("PARTNER_REVERSE");
    assertThat(status(txn)).isEqualTo("REVERSING");
  }

  @Test
  void noPartnerAnswerWithinTheReversingTimeoutGoesToManualReview() {
    String txn = committedTopupTheCoreRefuses();
    for (int i = 0; i < 12; i++) {
      clock.advance(Duration.ofSeconds(5));
      timers.sweep();
    }

    assertThat(status(txn)).isEqualTo("MANUAL_REVIEW");
    assertThat(pendingLeg(txn)).isNull();
    assertThat(lastStatus(txn).path("error").path("code").asString()).isEqualTo("MW-TMO-022");
    assertThat(reverses(txn)).isNotEmpty().allSatisfy(r -> assertThat(r.path("requestId").asString()).isEqualTo(reverseKey(txn)));
  }

  @Test
  void aDefinitePartnerRefusalEndsFailedWithoutTouchingTheHold() {
    String txn = committedTopupTheCoreRefuses();

    partnerReplies.handle(reverseReply(txn, "FAILED", "MW-PTR-GP-REVERSE_REFUSED"));

    assertThat(status(txn)).isEqualTo("FAILED");
    assertThat(pendingLeg(txn)).isNull();
    assertThat(commandTypes(txn)).doesNotContain("QueryTransaction", "ReleaseHold", "ReverseTransaction");
    assertThat(lastStatus(txn).path("error").path("code").asString()).isEqualTo("MW-PTR-GP-REVERSE_REFUSED");
  }

  /** The name-based UUID of {@code reverse:<txnId>}: the same key on every resend (PLT-025). */
  private static String reverseKey(String txnId) {
    return UUID.nameUUIDFromBytes(("reverse:" + txnId).getBytes(StandardCharsets.UTF_8)).toString();
  }

  /** Partner PAY succeeded and CommitHold was refused. The transaction is compensating. */
  private String committedTopupTheCoreRefuses() {
    TxnRequest req = TestData.topup(TestData.txnId(), "key-" + TestData.txnId(), 2_000, "01711000002");
    transactions.accept(req, "gw-1");
    String txn = req.txnId();
    coreEvents.answer(event("TransactionValidated", txn, txn + ":validate", null, "{\"fee\":{\"valueMinor\":200,\"currency\":\"BDT\"}}"));
    coreEvents.answer(event("FundsHeld", txn, txn + ":hold", null, "{\"holdId\":\"HOLD-CORE-7\"}"));
    partnerReplies.handle(payReply(txn));
    coreEvents.answer(event("CommitFailed", txn, txn + ":commit", "LIMIT_EXCEEDED_PER_TXN", "{}"));
    return txn;
  }

  private PartnerReply payReply(String txnId) {
    return json.readValue(
        "{\"schemaVersion\":1,\"family\":\"telco\",\"requestId\":\"" + PartnerRequestMapper.requestId(txnId, "PAY")
            + "\",\"txnId\":\"" + txnId + "\",\"correlationId\":\"corr-1\",\"partnerId\":\"gp\","
            + "\"repliedAt\":\"2026-11-03T08:15:33Z\",\"payload\":{\"operation\":\"PAY\",\"outcome\":\"SUCCESS\","
            + "\"partnerRef\":\"OCS-1\"}}",
        PartnerReply.class);
  }

  private PartnerReply reverseReply(String txnId, String outcome, String errorCode) {
    String payload = "{\"operation\":\"REVERSE\",\"outcome\":\"" + outcome + "\""
        + ("SUCCESS".equals(outcome) ? ",\"partnerRef\":\"OCS-REV-1\"" : "")
        + (errorCode == null ? "" : ",\"errorCode\":\"" + errorCode + "\",\"message\":\"Reverse refused\",\"retryable\":false")
        + "}";
    return json.readValue(
        "{\"schemaVersion\":1,\"family\":\"telco\",\"requestId\":\"" + reverseKey(txnId) + "\",\"txnId\":\"" + txnId
            + "\",\"correlationId\":\"corr-1\",\"partnerId\":\"gp\",\"repliedAt\":\"2026-11-03T08:15:40Z\","
            + "\"payload\":" + payload + "}",
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

  /** A QueryTransaction answer. {@code txnStatus} is the core's hold state (HELD, RELEASED, COMMITTED). */
  private CoreEvent status(String txnId, String commandId, String txnStatus) {
    return event("TransactionStatus", txnId, commandId, null, "{\"txnStatus\":\"" + txnStatus + "\"}");
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

  private List<JsonNode> reverses(String txnId) {
    return jdbc.queryForList(
            "select payload::text from outbox where topic = ? and msg_key = ? order by id",
            String.class, PARTNER_TOPIC, txnId)
        .stream()
        .map(json::readTree)
        .filter(n -> "REVERSE".equals(n.path("payload").path("operation").asString()))
        .toList();
  }

  private List<String> commandTypes(String txnId) {
    return coreEvents.commands(txnId).stream().map(c -> c.path("commandType").asString()).toList();
  }

  private JsonNode lastCommand(String txnId) {
    List<JsonNode> all = coreEvents.commands(txnId);
    return all.get(all.size() - 1);
  }
}
