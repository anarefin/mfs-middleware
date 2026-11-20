package mfs.mw.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.util.List;
import mfs.mw.orchestrator.CoreCalls;
import mfs.mw.orchestrator.MutableClock;
import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.TestInfrastructure;
import mfs.mw.orchestrator.contracts.ContractSchemas;
import mfs.mw.orchestrator.dto.CoreEvent;
import mfs.mw.orchestrator.dto.PartnerReply;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.mapper.PartnerRequestMapper;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hold expiry (ticket 95c, FRS 7.3): the core already freed the reservation and published
 * {@code HoldExpired}. A saga still waiting on its partner confirms that release. A saga whose
 * partner already succeeded stops in manual review and sends nothing more.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.orchestrator.consumers.enabled=false",
      "mw.orchestrator.timer.enabled=false"
    })
@Import({TestInfrastructure.class, CoreCalls.class, SagaServiceIT.TestClock.class})
class HoldExpirySagaIT {

  private static final String STATUS_SCHEMA = "events/mw-orchestrator/mw.txn.status.v1.schema.json";
  private static final String HOLD_ID = "HOLD-CORE-7";

  private final TransactionService transactions;
  private final CoreCalls coreEvents;
  private final CoreEventService kafkaEvents;
  private final PartnerReplyService partnerReplies;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  HoldExpirySagaIT(
      TransactionService transactions,
      CoreCalls coreEvents,
      CoreEventService kafkaEvents,
      PartnerReplyService partnerReplies,
      Clock clock,
      JdbcTemplate jdbc,
      JsonMapper json) {
    this.transactions = transactions;
    this.coreEvents = coreEvents;
    this.kafkaEvents = kafkaEvents;
    this.partnerReplies = partnerReplies;
    this.clock = (MutableClock) clock;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeEach
  void clean() {
    clock.set(TestData.NOW);
    for (String t : List.of("outbox", "inbox", "saga_timer", "saga_step", "idempotency", "transaction", "core_call", "partner_call")) {
      jdbc.update("delete from " + t);
    }
  }

  @Test
  void aHoldThatExpiresWhileWaitingOnThePartnerGoesToReleasing() {
    String txn = startToPartnerPending();

    kafkaEvents.handle(holdExpired(txn));

    assertThat(status(txn)).isEqualTo("RELEASING");
    JsonNode release = lastCommand(txn);
    assertThat(release.path("commandType").asString()).isEqualTo("ReleaseHold");
    assertThat(release.path("commandId").asString()).isEqualTo(txn + ":release");
    assertThat(release.path("payload").path("holdId").asString()).isEqualTo(HOLD_ID);
    JsonNode releasing = lastStatus(txn);
    assertThat(releasing.path("status").asString()).isEqualTo("RELEASING");
    assertThat(releasing.path("error").path("code").asString()).isEqualTo("MW-CORE-HOLD_EXPIRED");
    assertThat(releasing.path("error").path("retryable").asBoolean()).isFalse();
    assertThat(ContractSchemas.schema(STATUS_SCHEMA).validate(releasing)).isEmpty();

    coreEvents.answer(event("HoldReleased", txn, txn + ":release", null, "{}"));
    assertThat(status(txn)).isEqualTo("FAILED");
    assertThat(lastStatus(txn).path("error").path("code").asString()).isEqualTo("MW-CORE-HOLD_EXPIRED");
    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction", "HoldFunds", "ReleaseHold");
  }

  @Test
  void aRepeatedHoldExpiryConfirmsTheReleaseOnce() {
    String txn = startToPartnerPending();
    CoreEvent expired = holdExpired(txn);

    kafkaEvents.handle(expired);
    kafkaEvents.handle(expired);
    kafkaEvents.handle(holdExpired(txn, HOLD_ID, "evt-HoldExpired-again-" + txn));

    assertThat(status(txn)).isEqualTo("RELEASING");
    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction", "HoldFunds", "ReleaseHold");
  }

  @Test
  void aHoldThatExpiresBeforeThePartnerIsAskedIsReleasedWithTheEventsHold() {
    String txn = start();
    coreEvents.answer(event("TransactionValidated", txn, txn + ":validate", null, "{}"));
    assertThat(status(txn)).isEqualTo("HOLDING");

    kafkaEvents.handle(holdExpired(txn, "HOLD-FROM-EXPIRY", "evt-HoldExpired-holding-" + txn));

    assertThat(status(txn)).isEqualTo("RELEASING");
    assertThat(lastCommand(txn).path("commandType").asString()).isEqualTo("ReleaseHold");
    assertThat(lastCommand(txn).path("payload").path("holdId").asString()).isEqualTo("HOLD-FROM-EXPIRY");
  }

  @Test
  void anUnknownPartnerOutcomeWhoseHoldExpiresGoesToReleasing() {
    String txn = startToPartnerPending();
    partnerReplies.handle(reply(txn, "UNKNOWN", "MW-TMO-PARTNER_TIMEOUT"));
    assertThat(status(txn)).isEqualTo("UNKNOWN");

    kafkaEvents.handle(holdExpired(txn));

    assertThat(status(txn)).isEqualTo("RELEASING");
    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction", "HoldFunds", "ReleaseHold");
    assertThat(jdbc.queryForList("select step from saga_timer where txn_id = ?::uuid", String.class, txn))
        .containsExactly("RELEASING");
  }

  @Test
  void aHoldThatExpiresAfterThePartnerSucceededGoesToManualReview() {
    String txn = startToPartnerPending();
    partnerReplies.handle(reply(txn, "SUCCESS", null));
    assertThat(status(txn)).isEqualTo("COMMITTING");

    kafkaEvents.handle(holdExpired(txn));

    assertThat(status(txn)).isEqualTo("MANUAL_REVIEW");
    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction", "HoldFunds", "CommitHold");
    JsonNode review = lastStatus(txn);
    assertThat(review.path("error").path("code").asString()).isEqualTo("MW-CORE-HOLD_EXPIRED");
    assertThat(review.path("error").path("retryable").asBoolean()).isFalse();
    assertThat(ContractSchemas.schema(STATUS_SCHEMA).validate(review)).isEmpty();
  }

  @Test
  void aHoldExpiryForATransferThatHoldsNothingChangesNothing() {
    TxnRequest req = TestData.p2p(TestData.txnId(), "key-" + TestData.txnId(), 5_000);
    transactions.accept(req, "gw-1");
    coreEvents.answer(event("TransactionValidated", req.txnId(), req.txnId() + ":validate", null, "{}"));
    assertThat(status(req.txnId())).isEqualTo("POSTING");

    kafkaEvents.handle(holdExpired(req.txnId()));

    assertThat(status(req.txnId())).isEqualTo("POSTING");
    assertThat(commandTypes(req.txnId())).containsExactly("ValidateTransaction", "PostTransfer");
  }

  @Test
  void aHoldExpiryForAnUnknownTransactionIsDropped() {
    kafkaEvents.handle(holdExpired(TestData.txnId()));

    assertThat(jdbc.queryForObject("select count(*) from transaction", Long.class)).isZero();
  }

  @Test
  void aWalletStateEventDoesNotMoveALiveSaga() {
    String txn = startToPartnerPending();
    Logger logger = (Logger) LoggerFactory.getLogger(CoreEventService.class);
    Level previous = logger.getLevel();
    logger.setLevel(Level.DEBUG);
    ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    logger.addAppender(logs);
    try {
      kafkaEvents.handle(walletState(txn, "WalletStateChanged"));
      kafkaEvents.handle(walletState(txn, "WalletUnlocked"));
    } finally {
      logger.detachAppender(logs);
      logger.setLevel(previous);
    }

    assertThat(status(txn)).isEqualTo("PARTNER_PENDING");
    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction", "HoldFunds");
    assertThat(logs.list).allSatisfy(e -> {
      assertThat(String.valueOf(e.getKeyValuePairs())).doesNotContain("01711000001");
      assertThat(e.getFormattedMessage()).doesNotContain("01711000001");
    });
    assertThat(logs.list).anySatisfy(e -> assertThat(e.getKeyValuePairs()).anySatisfy(kv -> {
      assertThat(kv.key).isEqualTo("event");
      assertThat(kv.value).isEqualTo("core.wallet_state.consumed");
    }));
  }

  private String start() {
    TxnRequest req = TestData.topup(TestData.txnId(), "key-" + TestData.txnId(), 2_000, "01711000002");
    transactions.accept(req, "gw-1");
    return req.txnId();
  }

  private String startToPartnerPending() {
    String txn = start();
    coreEvents.answer(event("TransactionValidated", txn, txn + ":validate", null,
        "{\"fee\":{\"valueMinor\":200,\"currency\":\"BDT\"}}"));
    coreEvents.answer(event("FundsHeld", txn, txn + ":hold", null, "{\"holdId\":\"" + HOLD_ID + "\"}"));
    return txn;
  }

  private PartnerReply reply(String txnId, String outcome, String errorCode) {
    String payload = "{\"operation\":\"PAY\",\"outcome\":\"" + outcome + "\""
        + (errorCode == null ? "" : ",\"errorCode\":\"" + errorCode + "\",\"message\":\"Top-up refused\",\"retryable\":false")
        + ("SUCCESS".equals(outcome) ? ",\"partnerRef\":\"OCS-1\"" : "")
        + "}";
    return json.readValue(
        "{\"schemaVersion\":1,\"family\":\"telco\",\"requestId\":\""
            + PartnerRequestMapper.requestId(txnId, "PAY") + "\",\"txnId\":\"" + txnId
            + "\",\"correlationId\":\"corr-1\",\"partnerId\":\"gp\",\"repliedAt\":\"2026-11-03T08:15:33Z\","
            + "\"payload\":" + payload + "}",
        PartnerReply.class);
  }

  private String status(String txnId) {
    return jdbc.queryForObject("select status from transaction where txn_id = ?::uuid", String.class, txnId);
  }

  private List<JsonNode> outbox(String topic, String txnId) {
    return jdbc.queryForList(
            "select payload::text from outbox where topic = ? and msg_key = ? order by id", String.class, topic, txnId)
        .stream()
        .map(json::readTree)
        .toList();
  }

  private JsonNode lastStatus(String txnId) {
    List<JsonNode> all = outbox("mw.txn.status.v1", txnId);
    return all.get(all.size() - 1);
  }

  private List<JsonNode> commands(String txnId) {
    return coreEvents.commands(txnId);
  }

  private List<String> commandTypes(String txnId) {
    return commands(txnId).stream().map(c -> c.path("commandType").asString()).toList();
  }

  private JsonNode lastCommand(String txnId) {
    List<JsonNode> all = commands(txnId);
    return all.get(all.size() - 1);
  }

  private CoreEvent event(String type, String txnId, String commandId, String reason, String payload) {
    String outcome = reason == null ? "SUCCESS" : "FAILURE";
    String reasonJson = reason == null ? "" : ",\"reasonCode\":\"" + reason + "\"";
    return json.readValue(
        "{\"schemaVersion\":1,\"eventType\":\"" + type + "\",\"eventId\":\"evt-" + type + "-" + commandId
            + "\",\"commandId\":\"" + commandId + "\",\"txnId\":\"" + txnId
            + "\",\"coreRef\":\"CORE-7\",\"occurredAt\":\"2026-11-03T08:45:31Z\",\"outcome\":\""
            + outcome + "\"" + reasonJson + ",\"payload\":" + payload + "}",
        CoreEvent.class);
  }

  private CoreEvent holdExpired(String txnId) {
    return holdExpired(txnId, HOLD_ID, "evt-HoldExpired-" + txnId);
  }

  private CoreEvent holdExpired(String txnId, String holdId, String eventId) {
    return json.readValue(
        "{\"schemaVersion\":1,\"eventType\":\"HoldExpired\",\"eventId\":\"" + eventId
            + "\",\"commandId\":\"hold-expiry:" + holdId + "\",\"txnId\":\"" + txnId
            + "\",\"coreRef\":\"CORE-7\",\"occurredAt\":\"2026-11-03T08:45:31Z\",\"outcome\":\"SUCCESS\","
            + "\"payload\":{\"holdId\":\"" + holdId + "\",\"walletId\":\"01711000001\","
            + "\"amount\":{\"valueMinor\":2000,\"currency\":\"BDT\"},\"fee\":{\"valueMinor\":200,\"currency\":\"BDT\"},"
            + "\"expiresAt\":\"2026-11-03T08:45:30Z\"}}",
        CoreEvent.class);
  }

  /** A wallet-state event carrying an MSISDN the service must not log. */
  private CoreEvent walletState(String txnId, String type) {
    return json.readValue(
        "{\"schemaVersion\":1,\"eventType\":\"" + type + "\",\"eventId\":\"evt-" + type + "-" + txnId
            + "\",\"commandId\":\"wallet-state:" + txnId + "\",\"txnId\":\"" + txnId
            + "\",\"occurredAt\":\"2026-11-03T08:45:31Z\",\"outcome\":\"SUCCESS\",\"payload\":{"
            + "\"walletId\":\"01711000001\",\"msisdn\":\"01711000001\",\"walletType\":\"CUSTOMER\","
            + "\"status\":\"LOCKED\",\"hasPin\":false,\"version\":1,\"change\":\"STATUS_CHANGED\"}}",
        CoreEvent.class);
  }
}
