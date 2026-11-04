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
 * TOPUP (ticket 17, spec 2.1 and 7.2) through the service seams: intake, core events, partner
 * replies and the timer sweep, against real PostgreSQL. Output is read from the outbox: status,
 * core commands and partner requests.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.orchestrator.consumers.enabled=false",
      "mw.orchestrator.timer.enabled=false"
    })
@Import({TestInfrastructure.class, SagaServiceIT.TestClock.class})
class TopupSagaIT {

  private static final String STATUS_SCHEMA = "events/mw-orchestrator/mw.txn.status.v1.schema.json";
  private static final String COMMAND_SCHEMA = "events/mw-orchestrator/core.command.v1.schema.json";
  private static final String PARTNER_SCHEMA = "events/mw-orchestrator/mw.partner.request.v1.schema.json";
  private static final String AUDIT_SCHEMA = "events/mw-audit-dw/mw.audit.event.v1.schema.json";
  private static final String PARTNER_TOPIC = "mw.partner.request.telco.v1";

  private final TransactionService transactions;
  private final CoreEventService coreEvents;
  private final PartnerReplyService partnerReplies;
  private final SagaTimerService timers;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  private final io.micrometer.core.instrument.MeterRegistry meters;

  @Autowired
  TopupSagaIT(
      TransactionService transactions,
      CoreEventService coreEvents,
      PartnerReplyService partnerReplies,
      SagaTimerService timers,
      Clock clock,
      JdbcTemplate jdbc,
      JsonMapper json,
      io.micrometer.core.instrument.MeterRegistry meters) {
    this.transactions = transactions;
    this.coreEvents = coreEvents;
    this.partnerReplies = partnerReplies;
    this.timers = timers;
    this.clock = (MutableClock) clock;
    this.jdbc = jdbc;
    this.json = json;
    this.meters = meters;
  }

  private double counter(String name) {
    var c = meters.find(name).counter();
    return c == null ? 0 : c.count();
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

  private List<String> statuses(String txnId) {
    return outbox("mw.txn.status.v1", txnId).stream().map(n -> n.path("status").asString()).toList();
  }

  private JsonNode lastStatus(String txnId) {
    List<JsonNode> all = outbox("mw.txn.status.v1", txnId);
    return all.get(all.size() - 1);
  }

  private List<String> commandTypes(String txnId) {
    return outbox("core.wallet.command.v1", txnId).stream().map(c -> c.path("commandType").asString()).toList();
  }

  private JsonNode lastCommand(String txnId) {
    List<JsonNode> all = outbox("core.wallet.command.v1", txnId);
    return all.get(all.size() - 1);
  }

  private void assertAllValid(String txnId) {
    for (JsonNode n : outbox("mw.txn.status.v1", txnId)) {
      assertThat(ContractSchemas.schema(STATUS_SCHEMA).validate(n)).as(n.toString()).isEmpty();
    }
    for (JsonNode n : outbox("core.wallet.command.v1", txnId)) {
      assertThat(ContractSchemas.schema(COMMAND_SCHEMA).validate(n)).as(n.toString()).isEmpty();
    }
    for (JsonNode n : outbox(PARTNER_TOPIC, txnId)) {
      assertThat(ContractSchemas.schema(PARTNER_SCHEMA).validate(n)).as(n.toString()).isEmpty();
    }
    for (JsonNode n : outbox("mw.audit.event.v1", txnId)) {
      assertThat(ContractSchemas.schema(AUDIT_SCHEMA).validate(n)).as(n.toString()).isEmpty();
    }
  }

  private String status(String txnId) {
    return jdbc.queryForObject("select status from transaction where txn_id = ?::uuid", String.class, txnId);
  }

  private String start(long amount, String msisdn) {
    TxnRequest req = TestData.topup(TestData.txnId(), "key-" + TestData.txnId(), amount, msisdn);
    transactions.accept(req, "gw-1");
    return req.txnId();
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

  private PartnerReply reply(String txnId, String outcome, String errorCode, String partnerRef) {
    String payload =
        "{\"operation\":\"PAY\",\"outcome\":\"" + outcome + "\""
            + (errorCode == null ? "" : ",\"errorCode\":\"" + errorCode + "\",\"message\":\"Top-up refused\",\"retryable\":false")
            + (partnerRef == null ? "" : ",\"partnerRef\":\"" + partnerRef + "\"")
            + "}";
    return json.readValue(
        "{\"schemaVersion\":1,\"family\":\"telco\",\"requestId\":\"" + PartnerRequestMapper.requestId(txnId, "PAY") + "\",\"txnId\":\"" + txnId
            + "\",\"correlationId\":\"corr-1\",\"partnerId\":\"gp\",\"repliedAt\":\"2026-11-03T08:15:33Z\","
            + "\"payload\":" + payload + "}",
        PartnerReply.class);
  }

  /** Validate and hold succeed; the transaction waits for the partner. */
  private String startToPartnerPending(long amount) {
    String txn = start(amount, "01711000002");
    coreEvents.handle(event("TransactionValidated", txn, txn + ":validate", null, "{\"fee\":{\"valueMinor\":200,\"currency\":\"BDT\"}}"));
    coreEvents.handle(event("FundsHeld", txn, txn + ":hold", null, "{\"holdId\":\"HOLD-CORE-7\"}"));
    return txn;
  }

  // --- the hold path -------------------------------------------------------------------------

  @Test
  void aSuccessfulTopupHoldsCallsThePartnerAndCommits() {
    String txn = start(2_000, "01711000002");
    assertThat(statuses(txn)).containsExactly("RECEIVED", "VALIDATING");

    coreEvents.handle(event("TransactionValidated", txn, txn + ":validate", null, "{\"fee\":{\"valueMinor\":200,\"currency\":\"BDT\"}}"));
    assertThat(statuses(txn)).endsWith("HOLDING");
    JsonNode hold = lastCommand(txn);
    assertThat(hold.path("commandType").asString()).isEqualTo("HoldFunds");
    assertThat(hold.path("commandId").asString()).isEqualTo(txn + ":hold");
    assertThat(hold.path("payload").path("txnType").asString()).isEqualTo("TOPUP");
    assertThat(hold.path("payload").path("creditParty").path("type").asString()).isEqualTo("MSISDN");
    assertThat(hold.path("payload").path("encryptedPin").path("ciphertext").asString()).isEqualTo("ZmFrZS1jaXBoZXJ0ZXh0");

    coreEvents.handle(event("FundsHeld", txn, txn + ":hold", null, "{\"holdId\":\"HOLD-CORE-7\"}"));
    assertThat(statuses(txn)).endsWith("PARTNER_PENDING");
    List<JsonNode> requests = outbox(PARTNER_TOPIC, txn);
    assertThat(requests).hasSize(1);
    JsonNode request = requests.get(0);
    assertThat(request.path("family").asString()).isEqualTo("telco");
    assertThat(request.path("requestId").asString())
        .isEqualTo(java.util.UUID.nameUUIDFromBytes((txn + ":PAY").getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
    assertThat(request.path("payload").path("partnerTxnRef").asString()).isEqualTo("UPAY" + txn.replace("-", "").toUpperCase());
    assertThat(request.path("deadlineAt").asString()).isEqualTo(TestData.NOW.plusSeconds(30).toString());
    assertThat(request.path("payload").path("operation").asString()).isEqualTo("PAY");
    assertThat(request.path("payload").path("partner").path("partnerId").asString()).isEqualTo("gp");
    assertThat(request.path("payload").path("creditParty").path("id").asString()).isEqualTo("01711000002");
    assertThat(request.path("payload").path("amount").path("valueMinor").asLong()).isEqualTo(2_000);
    assertThat(request.toString()).doesNotContain("encryptedPin");

    partnerReplies.handle(reply(txn, "SUCCESS", null, "OCS-991"));
    assertThat(statuses(txn)).endsWith("COMMITTING");
    JsonNode commit = lastCommand(txn);
    assertThat(commit.path("commandType").asString()).isEqualTo("CommitHold");
    assertThat(commit.path("commandId").asString()).isEqualTo(txn + ":commit");
    assertThat(commit.path("payload").path("holdId").asString()).isEqualTo("HOLD-CORE-7");

    coreEvents.handle(event("HoldCommitted", txn, txn + ":commit", null, "{\"holdId\":\"HOLD-CORE-7\"}"));
    assertThat(statuses(txn))
        .containsExactly("RECEIVED", "VALIDATING", "HOLDING", "PARTNER_PENDING", "COMMITTING", "COMPLETED");
    JsonNode done = lastStatus(txn);
    assertThat(done.path("partnerRef").asString()).isEqualTo("OCS-991");
    assertThat(done.path("fee").path("valueMinor").asLong()).isEqualTo(200);
    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction", "HoldFunds", "CommitHold");
    assertThat(jdbc.queryForObject("select encrypted_pin is null from transaction where txn_id = ?::uuid", Boolean.class, txn)).isTrue();
    assertThat(jdbc.queryForObject("select count(*) from saga_timer", Long.class)).isZero();
    assertAllValid(txn);
  }

  @Test
  void aPartnerFailureReleasesTheHoldAndEndsFailedWithThePartnerError() {
    String txn = startToPartnerPending(2_000);

    partnerReplies.handle(reply(txn, "FAILED", "MW-PTR-GP-TOPUP_REJECTED", null));
    assertThat(statuses(txn)).endsWith("RELEASING");
    JsonNode release = lastCommand(txn);
    assertThat(release.path("commandType").asString()).isEqualTo("ReleaseHold");
    assertThat(release.path("commandId").asString()).isEqualTo(txn + ":release");
    assertThat(release.path("payload").path("holdId").asString()).isEqualTo("HOLD-CORE-7");

    coreEvents.handle(event("HoldReleased", txn, txn + ":release", null, "{\"holdId\":\"HOLD-CORE-7\"}"));
    assertThat(statuses(txn))
        .containsExactly("RECEIVED", "VALIDATING", "HOLDING", "PARTNER_PENDING", "RELEASING", "FAILED");
    JsonNode failed = lastStatus(txn);
    assertThat(failed.path("error").path("code").asString()).isEqualTo("MW-PTR-GP-TOPUP_REJECTED");
    assertThat(failed.path("error").path("retryable").asBoolean()).isFalse();
    assertThat(commandTypes(txn)).doesNotContain("CommitHold");
    assertAllValid(txn);
  }

  @Test
  void aPartnerErrorCodeOutsideTheContractIsReplacedByAnUnmappedPartnerCode() {
    String txn = startToPartnerPending(2_000);
    partnerReplies.handle(reply(txn, "FAILED", "not a code", null));
    assertThat(lastStatus(txn).path("error").path("code").asString()).isEqualTo("MW-PTR-GP-UNMAPPED");
  }

  @Test
  void aHoldRejectionEndsRejectedWithoutCallingThePartner() {
    String txn = start(2_000, "01711000002");
    coreEvents.handle(event("TransactionValidated", txn, txn + ":validate", null, "{}"));
    coreEvents.handle(event("HoldRejected", txn, txn + ":hold", "INSUFFICIENT_BALANCE", "{}"));

    assertThat(statuses(txn)).containsExactly("RECEIVED", "VALIDATING", "HOLDING", "REJECTED");
    assertThat(lastStatus(txn).path("error").path("code").asString()).isEqualTo("MW-CORE-INSUFFICIENT_BALANCE");
    assertThat(outbox(PARTNER_TOPIC, txn)).isEmpty();
    assertAllValid(txn);
  }

  // --- HOLDING timeout: ask the core what it holds -------------------------------------------

  @Test
  void aHoldingTimeoutQueriesTheCoreAndReleasesAHoldItFinds() {
    String txn = start(2_000, "01711000002");
    coreEvents.handle(event("TransactionValidated", txn, txn + ":validate", null, "{}"));
    clock.advance(Duration.ofSeconds(6));

    assertThat(timers.sweep()).isEqualTo(1);
    assertThat(status(txn)).isEqualTo("HOLDING");
    JsonNode query = lastCommand(txn);
    assertThat(query.path("commandType").asString()).isEqualTo("QueryTransaction");
    assertThat(query.path("commandId").asString()).startsWith(txn + ":query");
    assertThat(query.path("payload").isEmpty()).isTrue();

    coreEvents.handle(
        event("TransactionStatus", txn, query.path("commandId").asString(), null, "{\"txnStatus\":\"HELD\",\"holdId\":\"HOLD-LATE\"}"));
    assertThat(statuses(txn)).endsWith("HOLDING", "RELEASING");
    assertThat(lastCommand(txn).path("commandType").asString()).isEqualTo("ReleaseHold");
    assertThat(lastCommand(txn).path("payload").path("holdId").asString()).isEqualTo("HOLD-LATE");

    coreEvents.handle(event("HoldReleased", txn, txn + ":release", null, "{\"holdId\":\"HOLD-LATE\"}"));
    assertThat(status(txn)).isEqualTo("FAILED");
    assertThat(lastStatus(txn).path("error").path("code").asString()).isEqualTo("MW-TMO-002");
    assertThat(outbox(PARTNER_TOPIC, txn)).isEmpty();
    assertAllValid(txn);
  }

  @Test
  void aHoldingTimeoutWithNoHoldAtTheCoreEndsRejected() {
    String txn = start(2_000, "01711000002");
    coreEvents.handle(event("TransactionValidated", txn, txn + ":validate", null, "{}"));
    clock.advance(Duration.ofSeconds(6));
    timers.sweep();
    String queryId = lastCommand(txn).path("commandId").asString();

    coreEvents.handle(event("TransactionStatus", txn, queryId, null, "{\"txnStatus\":\"NOT_FOUND\"}"));

    assertThat(statuses(txn)).containsExactly("RECEIVED", "VALIDATING", "HOLDING", "REJECTED");
    assertThat(lastStatus(txn).path("error").path("code").asString()).isEqualTo("MW-TMO-002");
    assertThat(lastStatus(txn).path("error").path("retryable").asBoolean()).isTrue();
    assertAllValid(txn);
  }

  @Test
  void anUnansweredQueryIsAskedAgainWithAFreshCommandId() {
    String txn = start(2_000, "01711000002");
    coreEvents.handle(event("TransactionValidated", txn, txn + ":validate", null, "{}"));
    clock.advance(Duration.ofSeconds(6));
    timers.sweep();
    clock.advance(Duration.ofSeconds(6));
    timers.sweep();

    List<JsonNode> queries =
        outbox("core.wallet.command.v1", txn).stream()
            .filter(c -> "QueryTransaction".equals(c.path("commandType").asString()))
            .toList();
    assertThat(queries).hasSize(2);
    assertThat(queries).extracting(q -> q.path("commandId").asString()).doesNotHaveDuplicates();
    assertThat(status(txn)).isEqualTo("HOLDING");
  }

  @Test
  void aHoldPlacedAfterTheTransactionGaveUpIsReleased() {
    String txn = start(2_000, "01711000002");
    coreEvents.handle(event("TransactionValidated", txn, txn + ":validate", null, "{}"));
    clock.advance(Duration.ofSeconds(6));
    timers.sweep();
    coreEvents.handle(
        event("TransactionStatus", txn, lastCommand(txn).path("commandId").asString(), null, "{\"txnStatus\":\"NOT_FOUND\"}"));

    coreEvents.handle(event("FundsHeld", txn, txn + ":hold", null, "{\"holdId\":\"HOLD-ORPHAN\"}"));

    assertThat(status(txn)).isEqualTo("REJECTED");
    JsonNode release = lastCommand(txn);
    assertThat(release.path("commandType").asString()).isEqualTo("ReleaseHold");
    assertThat(release.path("payload").path("holdId").asString()).isEqualTo("HOLD-ORPHAN");
    assertAllValid(txn);
  }

  // --- commit / release retry until acknowledged ------------------------------------------------

  @Test
  void commitIsResentOnItsDeadlineUntilAcknowledged() {
    String txn = startToPartnerPending(2_000);
    partnerReplies.handle(reply(txn, "SUCCESS", null, "OCS-1"));
    clock.advance(Duration.ofSeconds(6));
    assertThat(timers.sweep()).isEqualTo(1);
    clock.advance(Duration.ofSeconds(6));
    assertThat(timers.sweep()).isEqualTo(1);

    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction", "HoldFunds", "CommitHold", "CommitHold", "CommitHold");
    assertThat(lastCommand(txn).path("commandId").asString()).isEqualTo(txn + ":commit");
    assertThat(statuses(txn)).endsWith("COMMITTING");

    coreEvents.handle(event("HoldCommitted", txn, txn + ":commit", null, "{}"));
    assertThat(status(txn)).isEqualTo("COMPLETED");
  }

  @Test
  void aCommitUnacknowledgedForSixtySecondsIsAlertedOnce() {
    String txn = startToPartnerPending(2_000);
    partnerReplies.handle(reply(txn, "SUCCESS", null, "OCS-1"));
    double before = counter("mw.txn.commit.unacknowledged");
    for (int i = 0; i < 14; i++) { // resends every 5 s for 70 s
      clock.advance(Duration.ofSeconds(5));
      timers.sweep();
    }
    assertThat(status(txn)).isEqualTo("COMMITTING");
    assertThat(counter("mw.txn.commit.unacknowledged")).isEqualTo(before + 1);
  }

  @Test
  void aCommitFailedIsCountedAsASevOneAlert() {
    String txn = startToPartnerPending(2_000);
    partnerReplies.handle(reply(txn, "SUCCESS", null, "OCS-1"));
    double before = counter("mw.txn.commit.failed");

    coreEvents.handle(event("CommitFailed", txn, txn + ":commit", "HOLD_NOT_FOUND", "{}"));

    assertThat(status(txn)).isEqualTo("MANUAL_REVIEW");
    assertThat(counter("mw.txn.commit.failed")).isEqualTo(before + 1);
  }

  @Test
  void releaseIsResentOnItsDeadlineUntilAcknowledged() {
    String txn = startToPartnerPending(2_000);
    partnerReplies.handle(reply(txn, "FAILED", "MW-PTR-GP-TOPUP_REJECTED", null));
    clock.advance(Duration.ofSeconds(6));
    assertThat(timers.sweep()).isEqualTo(1);

    assertThat(commandTypes(txn)).endsWith("ReleaseHold", "ReleaseHold");
    coreEvents.handle(event("HoldReleased", txn, txn + ":release", null, "{}"));
    assertThat(status(txn)).isEqualTo("FAILED");
  }

  // --- partner reply edge cases ----------------------------------------------------------------

  @Test
  void aLatePaySuccessAfterManualReviewIsAnOutcomeConflictAndChangesNothing() {
    String txn = startToPartnerPending(2_000);
    jdbc.update("update transaction set status = 'MANUAL_REVIEW' where txn_id = ?::uuid", txn);
    jdbc.update("delete from saga_timer");
    int statusesBefore = statuses(txn).size();
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PartnerReplyService.class);
    var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logs.start();
    logger.addAppender(logs);
    try {
      partnerReplies.handle(reply(txn, "SUCCESS", null, "OCS-LATE"));
    } finally {
      logger.detachAppender(logs);
    }

    assertThat(status(txn)).isEqualTo("MANUAL_REVIEW");
    assertThat(statuses(txn)).hasSize(statusesBefore);
    assertThat(commandTypes(txn)).doesNotContain("CommitHold");
    assertThat(logs.list)
        .anySatisfy(e -> {
          assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
          assertThat(e.getKeyValuePairs()).anySatisfy(kv -> {
            assertThat(kv.key).isEqualTo("event");
            assertThat(kv.value).isEqualTo("txn.outcome.conflict");
          });
        });
  }

  @Test
  void aRedeliveredPartnerReplyIsAppliedOnce() {
    String txn = startToPartnerPending(2_000);
    PartnerReply ok = reply(txn, "SUCCESS", null, "OCS-1");
    partnerReplies.handle(ok);
    partnerReplies.handle(ok);

    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction", "HoldFunds", "CommitHold");
  }

  @Test
  void anUnknownPartnerOutcomeMovesToUnknownAndKeepsTheHold() {
    String txn = startToPartnerPending(2_000);
    partnerReplies.handle(reply(txn, "UNKNOWN", "MW-TMO-PARTNER_TIMEOUT", null));

    assertThat(status(txn)).isEqualTo("UNKNOWN");
    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction", "HoldFunds");
    assertAllValid(txn);
  }

  @Test
  void aPendingReplyOrAReplyForAnotherStateChangesNothing() {
    String txn = startToPartnerPending(2_000);
    partnerReplies.handle(reply(txn, "PENDING", null, null));
    assertThat(status(txn)).isEqualTo("PARTNER_PENDING");

    String fresh = start(2_000, "01711000002");
    partnerReplies.handle(reply(fresh, "SUCCESS", null, "OCS-1"));
    assertThat(status(fresh)).isEqualTo("VALIDATING");

    partnerReplies.handle(reply(TestData.txnId(), "SUCCESS", null, "OCS-1")); // unknown txn
  }

  @Test
  void noPartnerReplyWithinThePartnerTimeoutMovesToUnknown() {
    String txn = startToPartnerPending(2_000);
    clock.advance(Duration.ofSeconds(31));
    assertThat(timers.sweep()).isEqualTo(1);

    assertThat(status(txn)).isEqualTo("UNKNOWN");
    assertThat(lastStatus(txn).path("previousStatus").asString()).isEqualTo("PARTNER_PENDING");
  }

  @Test
  void aTopupWithoutPartnerIsRejectedAtOnce() {
    TxnRequest t = TestData.topup(TestData.txnId(), "key-no-partner", 2_000, "01711000002");
    TxnRequest noPartner =
        new TxnRequest(1, t.txnId(), t.idempotencyKey(), t.correlationId(), t.channel(), "TOPUP",
            t.initiator(), t.debitParty(), t.creditParty(), t.amount(), null, null,
            t.encryptedPin(), null, null, null, t.requestedAt());
    transactions.accept(noPartner, null);

    assertThat(statuses(t.txnId())).containsExactly("RECEIVED", "REJECTED");
    assertThat(lastStatus(t.txnId()).path("error").path("code").asString()).isEqualTo("MW-VAL-012");
    assertThat(commandTypes(t.txnId())).isEmpty();
  }

  @Test
  void theSameKeyTwiceStartsOneTopup() {
    TxnRequest first = TestData.topup(TestData.txnId(), "key-topup-dup", 2_000, "01711000002");
    transactions.accept(first, "gw-1");
    TxnRequest again = TestData.topup(TestData.txnId(), "key-topup-dup", 2_000, "01711000002");
    transactions.accept(again, "gw-1");

    assertThat(jdbc.queryForObject("select count(*) from transaction", Long.class)).isEqualTo(1);
    assertThat(commandTypes(first.txnId())).containsExactly("ValidateTransaction");
  }
}
