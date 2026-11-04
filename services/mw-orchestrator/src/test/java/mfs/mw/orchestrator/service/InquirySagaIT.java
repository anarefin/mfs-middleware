package mfs.mw.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import mfs.mw.orchestrator.MutableClock;
import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.TestInfrastructure;
import mfs.mw.orchestrator.contracts.ContractSchemas;
import mfs.mw.orchestrator.dto.CoreEvent;
import mfs.mw.orchestrator.dto.PartnerRef;
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
 * Unknown outcomes (ticket 18, spec 7.4) through the service seams against real PostgreSQL: a
 * transaction in UNKNOWN is asked about on the inquiry schedule (the partner for TOPUP, the core
 * for P2P) and leaves UNKNOWN by commit, release, completion, failure or MANUAL_REVIEW. Output is
 * read from the outbox. Partner {@code tsqfast} has a schedule shortened to seconds in the test
 * {@code application.yml}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.orchestrator.consumers.enabled=false",
      "mw.orchestrator.timer.enabled=false"
    })
@Import({TestInfrastructure.class, SagaServiceIT.TestClock.class})
class InquirySagaIT {

  private static final String STATUS_SCHEMA = "events/mw-orchestrator/mw.txn.status.v1.schema.json";
  private static final String COMMAND_SCHEMA = "events/mw-orchestrator/core.command.v1.schema.json";
  private static final String PARTNER_SCHEMA = "events/mw-orchestrator/mw.partner.request.v1.schema.json";
  private static final String REPLY_SCHEMA = "events/mw-orchestrator/mw.partner.reply.v1.schema.json";
  private static final String PARTNER_TOPIC = "mw.partner.request.telco.v1";

  private final TransactionService transactions;
  private final CoreEventService coreEvents;
  private final PartnerReplyService partnerReplies;
  private final SagaTimerService timers;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  private final MeterRegistry meters;

  @Autowired
  InquirySagaIT(
      TransactionService transactions,
      CoreEventService coreEvents,
      PartnerReplyService partnerReplies,
      SagaTimerService timers,
      Clock clock,
      JdbcTemplate jdbc,
      JsonMapper json,
      MeterRegistry meters) {
    this.transactions = transactions;
    this.coreEvents = coreEvents;
    this.partnerReplies = partnerReplies;
    this.timers = timers;
    this.clock = (MutableClock) clock;
    this.jdbc = jdbc;
    this.json = json;
    this.meters = meters;
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
            "select payload::text from outbox where topic = ? and msg_key = ? order by id", String.class, topic, txnId)
        .stream()
        .map(json::readTree)
        .toList();
  }

  /** Core commands on both command topics, in outbox order (ticket 50: hold, commit, release on core.payment). */
  private List<JsonNode> coreCommands(String txnId) {
    return jdbc.queryForList(
            "select payload::text from outbox where topic in ('core.wallet.command.v1', 'core.payment.command.v1')"
                + " and msg_key = ? order by id",
            String.class,
            txnId)
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

  private List<String> commandTypes(String txnId) {
    return coreCommands(txnId).stream().map(c -> c.path("commandType").asString()).toList();
  }

  private JsonNode lastCommand(String txnId) {
    List<JsonNode> all = coreCommands(txnId);
    return all.get(all.size() - 1);
  }

  private List<JsonNode> inquiries(String txnId) {
    return outbox(PARTNER_TOPIC, txnId).stream()
        .filter(r -> "STATUS_QUERY".equals(r.path("payload").path("operation").asString()))
        .toList();
  }

  private void assertAllValid(String txnId) {
    for (JsonNode n : outbox("mw.txn.status.v1", txnId)) {
      assertThat(ContractSchemas.schema(STATUS_SCHEMA).validate(n)).as(n.toString()).isEmpty();
    }
    for (JsonNode n : coreCommands(txnId)) {
      assertThat(ContractSchemas.schema(COMMAND_SCHEMA).validate(n)).as(n.toString()).isEmpty();
    }
    for (JsonNode n : outbox(PARTNER_TOPIC, txnId)) {
      assertThat(ContractSchemas.schema(PARTNER_SCHEMA).validate(n)).as(n.toString()).isEmpty();
    }
  }

  private String status(String txnId) {
    return jdbc.queryForObject("select status from transaction where txn_id = ?::uuid", String.class, txnId);
  }

  private long timersOf(String txnId) {
    return jdbc.queryForObject("select count(*) from saga_timer where txn_id = ?::uuid", Long.class, txnId);
  }

  private CoreEvent event(String type, String txnId, String commandId, String reason, String payload) {
    String outcome = reason == null ? "SUCCESS" : "FAILURE";
    String reasonJson = reason == null ? "" : ",\"reasonCode\":\"" + reason + "\"";
    return json.readValue(
        "{\"schemaVersion\":1,\"eventType\":\"" + type + "\",\"eventId\":\"evt-" + UUID.randomUUID()
            + "\",\"commandId\":\"" + commandId + "\",\"txnId\":\"" + txnId
            + "\",\"coreRef\":\"CORE-7\",\"occurredAt\":\"2026-11-03T08:15:31Z\",\"outcome\":\""
            + outcome + "\"" + reasonJson + ",\"payload\":" + payload + "}",
        CoreEvent.class);
  }

  /** A reply to {@code requestId}; {@code extra} is appended to the payload object. */
  private PartnerReply reply(String txnId, String requestId, String operation, String outcome, String extra) {
    String payload = "{\"operation\":\"" + operation + "\",\"outcome\":\"" + outcome + "\"" + extra + "}";
    String body =
        "{\"schemaVersion\":1,\"family\":\"telco\",\"requestId\":\"" + requestId + "\",\"txnId\":\"" + txnId
            + "\",\"correlationId\":\"corr-1\",\"partnerId\":\"gp\",\"repliedAt\":\"2026-11-03T08:15:33Z\","
            + "\"payload\":" + payload + "}";
    assertThat(ContractSchemas.schema(REPLY_SCHEMA).validate(json.readTree(body))).as(body).isEmpty();
    return json.readValue(body, PartnerReply.class);
  }

  private PartnerReply payReply(String txnId, String outcome, String extra) {
    return reply(txnId, PartnerRequestMapper.requestId(txnId, "PAY"), "PAY", outcome, extra);
  }

  /** The answer to the latest STATUS_QUERY of {@code txnId}. */
  private PartnerReply inquiryReply(String txnId, String outcome, String extra) {
    List<JsonNode> sent = inquiries(txnId);
    String requestId = sent.get(sent.size() - 1).path("requestId").asString();
    return reply(txnId, requestId, "STATUS_QUERY", outcome, extra);
  }

  private String startTopup(String partnerId) {
    TxnRequest t = TestData.topup(TestData.txnId(), "key-" + TestData.txnId(), 2_000, "01711000002");
    TxnRequest req =
        new TxnRequest(1, t.txnId(), t.idempotencyKey(), t.correlationId(), t.channel(), t.txnType(),
            t.initiator(), t.debitParty(), t.creditParty(), t.amount(),
            new PartnerRef(partnerId, "PREPAID", null), null, t.encryptedPin(), t.locale(), null, null,
            t.requestedAt());
    transactions.accept(req, "gw-1");
    String txn = req.txnId();
    coreEvents.handle(event("TransactionValidated", txn, txn + ":validate", null, "{\"fee\":{\"valueMinor\":200,\"currency\":\"BDT\"}}"));
    coreEvents.handle(event("FundsHeld", txn, txn + ":hold", null, "{\"holdId\":\"HOLD-CORE-7\"}"));
    assertThat(status(txn)).isEqualTo("PARTNER_PENDING");
    return txn;
  }

  /** A TOPUP whose PAY reply never comes: the partner deadline moves it to UNKNOWN. */
  private String topupLostReply(String partnerId) {
    String txn = startTopup(partnerId);
    clock.advance(Duration.ofSeconds(31));
    timers.sweep();
    assertThat(status(txn)).isEqualTo("UNKNOWN");
    return txn;
  }

  private String startP2pToUnknown() {
    TxnRequest req = TestData.p2p(TestData.txnId(), "key-" + TestData.txnId(), 5_000);
    transactions.accept(req, "gw-1");
    String txn = req.txnId();
    coreEvents.handle(event("TransactionValidated", txn, txn + ":validate", null, "{}"));
    clock.advance(Duration.ofSeconds(6));
    timers.sweep();
    assertThat(status(txn)).isEqualTo("UNKNOWN");
    return txn;
  }

  private void advanceAndSweep(Duration d) {
    clock.advance(d);
    timers.sweep();
  }

  // --- TOPUP: the partner is asked ------------------------------------------------------------

  @Test
  void aLostPartnerReplyIsRecoveredByStatusInquiryAndCommitted() {
    String txn = topupLostReply("gp");
    assertThat(lastStatus(txn).path("previousStatus").asString()).isEqualTo("PARTNER_PENDING");
    assertThat(timersOf(txn)).isOne();

    // nothing is asked before the first slot (30 s)
    advanceAndSweep(Duration.ofSeconds(29));
    assertThat(inquiries(txn)).isEmpty();
    advanceAndSweep(Duration.ofSeconds(1));

    List<JsonNode> sent = inquiries(txn);
    assertThat(sent).hasSize(1);
    JsonNode q = sent.get(0);
    assertThat(q.path("family").asString()).isEqualTo("telco");
    assertThat(q.path("requestId").asString()).isNotEqualTo(PartnerRequestMapper.requestId(txn, "PAY"));
    assertThat(q.path("payload").path("partnerTxnRef").asString()).isEqualTo(PartnerRequestMapper.partnerTxnRef(txn));
    assertThat(q.path("payload").path("partner").path("partnerId").asString()).isEqualTo("gp");
    assertThat(q.toString()).doesNotContain("encryptedPin");
    assertThat(status(txn)).isEqualTo("UNKNOWN");

    partnerReplies.handle(inquiryReply(txn, "SUCCESS", ",\"partnerRef\":\"OCS-TSQ-1\""));
    assertThat(statuses(txn)).endsWith("UNKNOWN", "COMMITTING");
    assertThat(lastCommand(txn).path("commandType").asString()).isEqualTo("CommitHold");
    assertThat(lastCommand(txn).path("payload").path("holdId").asString()).isEqualTo("HOLD-CORE-7");

    coreEvents.handle(event("HoldCommitted", txn, txn + ":commit", null, "{}"));
    assertThat(statuses(txn))
        .containsExactly("RECEIVED", "VALIDATING", "HOLDING", "PARTNER_PENDING", "UNKNOWN", "COMMITTING", "COMPLETED");
    assertThat(lastStatus(txn).path("partnerRef").asString()).isEqualTo("OCS-TSQ-1");
    assertThat(timersOf(txn)).isZero();
    assertAllValid(txn);
  }

  @Test
  void anUnknownPartnerReplyIsAskedAboutAndAFailureReleasesTheHold() {
    String txn = startTopup("gp");
    partnerReplies.handle(payReply(txn, "UNKNOWN", ",\"errorCode\":\"MW-TMO-PARTNER_TIMEOUT\""));
    assertThat(status(txn)).isEqualTo("UNKNOWN");
    advanceAndSweep(Duration.ofSeconds(30));

    partnerReplies.handle(
        inquiryReply(txn, "FAILED", ",\"errorCode\":\"MW-PTR-GP-TOPUP_REJECTED\",\"message\":\"refused\",\"retryable\":false"));
    assertThat(statuses(txn)).endsWith("UNKNOWN", "RELEASING");
    assertThat(lastCommand(txn).path("commandType").asString()).isEqualTo("ReleaseHold");

    coreEvents.handle(event("HoldReleased", txn, txn + ":release", null, "{}"));
    assertThat(status(txn)).isEqualTo("FAILED");
    assertThat(lastStatus(txn).path("error").path("code").asString()).isEqualTo("MW-PTR-GP-TOPUP_REJECTED");
    assertAllValid(txn);
  }

  @Test
  void aStillPendingAnswerWaitsForTheNextSlotWithAFreshRequest() {
    String txn = topupLostReply("gp");
    advanceAndSweep(Duration.ofSeconds(30));
    partnerReplies.handle(inquiryReply(txn, "PENDING", ""));
    partnerReplies.handle(inquiryReply(txn, "UNKNOWN", ",\"errorCode\":\"MW-TMO-PARTNER_TIMEOUT\""));
    assertThat(status(txn)).isEqualTo("UNKNOWN");

    advanceAndSweep(Duration.ofSeconds(59));
    assertThat(inquiries(txn)).hasSize(1);
    advanceAndSweep(Duration.ofSeconds(1));
    assertThat(inquiries(txn)).hasSize(2);
    assertThat(inquiries(txn)).extracting(r -> r.path("requestId").asString()).doesNotHaveDuplicates();
    advanceAndSweep(Duration.ofMinutes(2));
    assertThat(inquiries(txn)).hasSize(3);
    assertThat(statuses(txn)).endsWith("UNKNOWN").filteredOn("UNKNOWN"::equals).hasSize(1);
  }

  @Test
  void notFoundCountsOnlyOnceThePayRequestCanNoLongerArrive() {
    String txn = startTopup("gp");
    // the adapter answers UNKNOWN at once (5xx after send)
    partnerReplies.handle(payReply(txn, "UNKNOWN", ",\"errorCode\":\"MW-TMO-PARTNER_HTTP_5XX\""));
    advanceAndSweep(Duration.ofSeconds(30));

    // 30 s after the PAY: its deadline (30 s) plus the grace (30 s) have not passed
    partnerReplies.handle(inquiryReply(txn, "NOT_FOUND", ""));
    assertThat(status(txn)).isEqualTo("UNKNOWN");

    advanceAndSweep(Duration.ofMinutes(1));
    partnerReplies.handle(inquiryReply(txn, "NOT_FOUND", ""));
    assertThat(statuses(txn)).endsWith("UNKNOWN", "RELEASING");
    coreEvents.handle(event("HoldReleased", txn, txn + ":release", null, "{}"));
    JsonNode failed = lastStatus(txn);
    assertThat(failed.path("status").asString()).isEqualTo("FAILED");
    assertThat(failed.path("error").path("code").asString()).isEqualTo("MW-TMO-003");
    assertThat(failed.path("error").path("retryable").asBoolean()).isTrue();
    assertAllValid(txn);
  }

  @Test
  void aLatePayReplyWithADefiniteOutcomeResolvesTheUnknown() {
    String txn = topupLostReply("gp");
    partnerReplies.handle(payReply(txn, "SUCCESS", ",\"partnerRef\":\"OCS-LATE\""));
    assertThat(statuses(txn)).endsWith("UNKNOWN", "COMMITTING");
    assertThat(timersOf(txn)).isOne(); // the commit retry, not an inquiry
  }

  @Test
  void anExhaustedScheduleHandsTheTransactionToAnOperator() {
    // tsqfast: inquiries after 1 s and 2 s, cut-off 5 s after the outcome became unknown
    String txn = topupLostReply("tsqfast");
    advanceAndSweep(Duration.ofSeconds(1));
    assertThat(inquiries(txn)).hasSize(1);
    partnerReplies.handle(inquiryReply(txn, "PENDING", ""));
    advanceAndSweep(Duration.ofSeconds(2));
    assertThat(inquiries(txn)).hasSize(2);
    advanceAndSweep(Duration.ofSeconds(1));
    assertThat(status(txn)).isEqualTo("UNKNOWN");

    advanceAndSweep(Duration.ofSeconds(1));

    assertThat(statuses(txn)).endsWith("UNKNOWN", "MANUAL_REVIEW");
    JsonNode review = lastStatus(txn);
    assertThat(review.path("error").path("code").asString()).isEqualTo("MW-TMO-004");
    assertThat(review.path("error").path("retryable").asBoolean()).isFalse();
    assertThat(inquiries(txn)).hasSize(2);
    assertThat(timersOf(txn)).isZero();
    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction", "HoldFunds");

    // an answer after the hand-over changes nothing: the operator decides
    partnerReplies.handle(inquiryReply(txn, "SUCCESS", ",\"partnerRef\":\"OCS-TOO-LATE\""));
    assertThat(status(txn)).isEqualTo("MANUAL_REVIEW");
    assertAllValid(txn);
  }

  // --- P2P: the core is asked -------------------------------------------------------------------

  @Test
  void aLostPostReplyIsFoundByQueryTransactionAndCompletes() {
    String txn = startP2pToUnknown();
    advanceAndSweep(Duration.ofSeconds(30));

    JsonNode q = lastCommand(txn);
    assertThat(q.path("commandType").asString()).isEqualTo("QueryTransaction");
    assertThat(q.path("commandId").asString()).startsWith(txn + ":query");

    coreEvents.handle(event("TransactionStatus", txn, q.path("commandId").asString(), null, "{\"txnStatus\":\"POSTED\"}"));

    assertThat(statuses(txn)).containsExactly("RECEIVED", "VALIDATING", "POSTING", "UNKNOWN", "COMPLETED");
    assertThat(timersOf(txn)).isZero();
    assertThat(jdbc.queryForObject("select encrypted_pin is null from transaction where txn_id = ?::uuid", Boolean.class, txn)).isTrue();
    assertAllValid(txn);
  }

  @Test
  void aTransferTheCoreNeverSawFailsOnceItsCommandHasExpired() {
    String txn = startP2pToUnknown();
    advanceAndSweep(Duration.ofSeconds(30));
    coreEvents.handle(event("TransactionStatus", txn, lastCommand(txn).path("commandId").asString(), null, "{\"txnStatus\":\"VALIDATED\"}"));
    assertThat(status(txn)).isEqualTo("UNKNOWN"); // PostTransfer may still arrive (60 s TTL)

    advanceAndSweep(Duration.ofMinutes(1));
    coreEvents.handle(event("TransactionStatus", txn, lastCommand(txn).path("commandId").asString(), null, "{\"txnStatus\":\"NOT_FOUND\"}"));

    assertThat(statuses(txn)).endsWith("UNKNOWN", "FAILED");
    assertThat(lastStatus(txn).path("error").path("code").asString()).isEqualTo("MW-TMO-003");
    assertAllValid(txn);
  }

  @Test
  void aTransferTheCoreRejectedFails() {
    String txn = startP2pToUnknown();
    advanceAndSweep(Duration.ofSeconds(30));
    coreEvents.handle(event("TransactionStatus", txn, lastCommand(txn).path("commandId").asString(), null, "{\"txnStatus\":\"REJECTED\"}"));
    assertThat(statuses(txn)).endsWith("UNKNOWN", "FAILED");
  }

  @Test
  void aCoreAnswerThatFitsNoOutcomeGoesToManualReview() {
    String txn = startP2pToUnknown();
    advanceAndSweep(Duration.ofSeconds(30));
    coreEvents.handle(event("TransactionStatus", txn, lastCommand(txn).path("commandId").asString(), null, "{\"txnStatus\":\"HELD\",\"holdId\":\"HOLD-X\"}"));

    assertThat(statuses(txn)).endsWith("UNKNOWN", "MANUAL_REVIEW");
    assertThat(lastStatus(txn).path("error").path("code").asString()).isEqualTo("MW-TMO-005");
    assertAllValid(txn);
  }

  // --- commit / release refused by the core ------------------------------------------------------

  @Test
  void aCommitTheCoreRefusesGoesToManualReview() {
    String txn = startTopup("gp");
    partnerReplies.handle(payReply(txn, "SUCCESS", ",\"partnerRef\":\"OCS-1\""));
    coreEvents.handle(event("CommitFailed", txn, txn + ":commit", "HOLD_RELEASED", "{}"));

    assertThat(statuses(txn)).endsWith("COMMITTING", "MANUAL_REVIEW");
    assertThat(lastStatus(txn).path("error").path("code").asString()).isEqualTo("MW-CORE-HOLD_RELEASED");
    assertThat(timersOf(txn)).isZero();
    assertAllValid(txn);
  }

  @Test
  void aReleaseTheCoreRefusesGoesToManualReview() {
    String txn = startTopup("gp");
    partnerReplies.handle(payReply(txn, "FAILED", ",\"errorCode\":\"MW-PTR-GP-TOPUP_REJECTED\""));
    coreEvents.handle(event("ReleaseFailed", txn, txn + ":release", "HOLD_ALREADY_COMMITTED", "{}"));

    assertThat(statuses(txn)).endsWith("RELEASING", "MANUAL_REVIEW");
    assertThat(lastStatus(txn).path("error").path("code").asString()).isEqualTo("MW-CORE-HOLD_ALREADY_COMMITTED");
    assertAllValid(txn);
  }

  // --- metrics -------------------------------------------------------------------------------

  @Test
  void theBacklogGaugesCountOpenUnknownAndManualReviewTransactions() {
    assertThat(meters.get("mw.txn.unknown.open").gauge().value()).isZero();
    assertThat(meters.get("mw.manual.review.open").gauge().value()).isZero();

    topupLostReply("gp");
    startP2pToUnknown();
    String review = topupLostReply("tsqfast");
    advanceAndSweep(Duration.ofSeconds(6));
    advanceAndSweep(Duration.ofSeconds(6));
    assertThat(status(review)).isEqualTo("MANUAL_REVIEW");

    assertThat(meters.get("mw.txn.unknown.open").gauge().value()).isEqualTo(2);
    assertThat(meters.get("mw.manual.review.open").gauge().value()).isEqualTo(1);
  }
}
