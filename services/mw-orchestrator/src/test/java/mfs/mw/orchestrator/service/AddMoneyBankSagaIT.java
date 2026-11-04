package mfs.mw.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import mfs.mw.orchestrator.MutableClock;
import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.TestInfrastructure;
import mfs.mw.orchestrator.contracts.ContractSchemas;
import mfs.mw.orchestrator.dto.CoreEvent;
import mfs.mw.orchestrator.dto.PartnerInbound;
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
 * ADD_MONEY_BANK / C2W (ticket 23, spec WP16) through the service seams: validate, debit the bank
 * account through the bank family, the bank confirms by callback, CreditWallet. No hold at any
 * point. Output is read from the outbox and validated against the owned contracts.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.orchestrator.consumers.enabled=false",
      "mw.orchestrator.timer.enabled=false"
    })
@Import({TestInfrastructure.class, SagaServiceIT.TestClock.class})
class AddMoneyBankSagaIT {

  private static final String STATUS_SCHEMA = "events/mw-orchestrator/mw.txn.status.v1.schema.json";
  private static final String PARTNER_SCHEMA = "events/mw-orchestrator/mw.partner.request.v1.schema.json";
  private static final String COMMAND_SCHEMA = "events/mw-orchestrator/core.command.v1.schema.json";
  private static final String INBOUND_SCHEMA = "events/mw-orchestrator/mw.partner.inbound.v1.schema.json";
  private static final String PARTNER_TOPIC = "mw.partner.request.bank.v1";
  private static final String COMMANDS = "core.wallet.command.v1";
  private static final String ACCOUNT = "1234567890123";

  private final TransactionService transactions;
  private final CoreEventService coreEvents;
  private final PartnerReplyService partnerReplies;
  private final PartnerInboundService inbound;
  private final SagaTimerService timers;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  AddMoneyBankSagaIT(
      TransactionService transactions,
      CoreEventService coreEvents,
      PartnerReplyService partnerReplies,
      PartnerInboundService inbound,
      SagaTimerService timers,
      Clock clock,
      JdbcTemplate jdbc,
      JsonMapper json) {
    this.transactions = transactions;
    this.coreEvents = coreEvents;
    this.partnerReplies = partnerReplies;
    this.inbound = inbound;
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

  private List<String> commandTypes(String txnId) {
    return outbox(COMMANDS, txnId).stream().map(n -> n.path("commandType").asString()).toList();
  }

  private JsonNode last(String topic, String txnId) {
    List<JsonNode> all = outbox(topic, txnId);
    return all.get(all.size() - 1);
  }

  private void assertAllValid(String txnId) {
    for (JsonNode n : outbox("mw.txn.status.v1", txnId)) {
      assertThat(ContractSchemas.schema(STATUS_SCHEMA).validate(n)).as(n.toString()).isEmpty();
    }
    for (JsonNode n : outbox(PARTNER_TOPIC, txnId)) {
      assertThat(ContractSchemas.schema(PARTNER_SCHEMA).validate(n)).as(n.toString()).isEmpty();
    }
    for (JsonNode n : outbox(COMMANDS, txnId)) {
      assertThat(ContractSchemas.schema(COMMAND_SCHEMA).validate(n)).as(n.toString()).isEmpty();
    }
  }

  private CoreEvent event(String type, String txnId, String commandId, String outcome, String reason) {
    return json.readValue(
        "{\"schemaVersion\":1,\"eventType\":\"" + type + "\",\"eventId\":\"evt-" + type + "-" + commandId
            + "\",\"commandId\":\"" + commandId + "\",\"txnId\":\"" + txnId
            + "\",\"coreRef\":\"CORE-23\",\"occurredAt\":\"2026-11-03T08:15:31Z\",\"outcome\":\"" + outcome + "\""
            + (reason == null ? "" : ",\"reasonCode\":\"" + reason + "\"")
            + ",\"payload\":{}}",
        CoreEvent.class);
  }

  private PartnerReply reply(String txnId, String requestId, String op, String outcome, String errorCode, String ref) {
    String payload =
        "{\"operation\":\"" + op + "\",\"outcome\":\"" + outcome + "\""
            + (errorCode == null ? "" : ",\"errorCode\":\"" + errorCode + "\",\"message\":\"Refused\",\"retryable\":false")
            + (ref == null ? "" : ",\"partnerRef\":\"" + ref + "\"")
            + "}";
    return json.readValue(
        "{\"schemaVersion\":1,\"family\":\"bank\",\"requestId\":\"" + requestId + "\",\"txnId\":\"" + txnId
            + "\",\"correlationId\":\"corr-1\",\"partnerId\":\"c2wbank\",\"repliedAt\":\"2026-11-03T08:15:33Z\","
            + "\"payload\":" + payload + "}",
        PartnerReply.class);
  }

  /** A callback as mw-adapter-bank publishes it (inboundId from partner, reference, outcome). */
  private PartnerInbound callback(String txnId, String outcome, String partnerRef, String errorCode) {
    String inboundId = UUID.nameUUIDFromBytes(("c2wbank:" + txnId + ":" + outcome).getBytes()).toString();
    String body =
        "{\"schemaVersion\":1,\"family\":\"bank\",\"inboundId\":\"" + inboundId + "\",\"txnId\":\"" + txnId
            + "\",\"correlationId\":\"corr-cb\",\"partnerId\":\"c2wbank\",\"partnerRef\":\"" + partnerRef
            + "\",\"receivedAt\":\"2026-11-03T08:15:40Z\",\"payload\":{\"kind\":\"CALLBACK\",\"outcome\":\""
            + outcome + "\",\"partnerCode\":\"" + ("FAILED".equals(outcome) ? "4002" : "000") + "\""
            + (errorCode == null ? "" : ",\"errorCode\":\"" + errorCode + "\"") + "}}";
    assertThat(ContractSchemas.schema(INBOUND_SCHEMA).validate(json.readTree(body))).as(body).isEmpty();
    return json.readValue(body, PartnerInbound.class);
  }

  /** Accepted, validated and the debit asked of the bank, which accepted it (202 PENDING). */
  private String toPartnerPending() {
    TxnRequest req = TestData.addMoneyBank(TestData.txnId(), "key-" + TestData.txnId(), 50_000, ACCOUNT);
    transactions.accept(req, "gw-1");
    String txn = req.txnId();
    coreEvents.handle(event("TransactionValidated", txn, txn + ":validate", "SUCCESS", null));
    partnerReplies.handle(reply(txn, PartnerRequestMapper.requestId(txn, "PAY"), "PAY", "PENDING", null, "C2W-1"));
    assertThat(statuses(txn)).endsWith("PARTNER_PENDING");
    return txn;
  }

  @Test
  void theBankIsAskedToDebitTheAccountWithoutAnyHold() {
    String txn = toPartnerPending();

    JsonNode pay = last(PARTNER_TOPIC, txn);
    assertThat(pay.path("family").asString()).isEqualTo("bank");
    assertThat(pay.path("payload").path("operation").asString()).isEqualTo("PAY");
    assertThat(pay.path("payload").path("txnType").asString()).isEqualTo("ADD_MONEY_BANK");
    assertThat(pay.path("payload").path("partner").path("partnerId").asString()).isEqualTo("c2wbank");
    assertThat(pay.path("payload").path("debitParty").path("type").asString()).isEqualTo("BANK_ACCOUNT");
    assertThat(pay.path("payload").path("debitParty").path("id").asString()).isEqualTo(ACCOUNT);
    assertThat(pay.path("payload").path("creditParty").path("id").asString()).isEqualTo("01711000001");
    assertThat(pay.toString()).doesNotContain("encryptedPin");
    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction");
    assertThat(statuses(txn)).containsExactly("RECEIVED", "VALIDATING", "PARTNER_PENDING");
    assertAllValid(txn);
  }

  @Test
  void theBanksSuccessCallbackCreditsTheWalletOnce() {
    String txn = toPartnerPending();

    inbound.handle(callback(txn, "SUCCESS", "C2W-1", null));
    JsonNode credit = last(COMMANDS, txn);
    assertThat(credit.path("commandType").asString()).isEqualTo("CreditWallet");
    assertThat(credit.path("commandId").asString()).isEqualTo(txn + ":credit");
    assertThat(credit.path("payload").path("creditParty").path("id").asString()).isEqualTo("01711000001");
    assertThat(credit.path("payload").path("amount").path("valueMinor").asLong()).isEqualTo(50_000);
    assertThat(credit.toString()).doesNotContain("encryptedPin");

    // the bank replays its callback: same inboundId, dropped, no second CreditWallet
    inbound.handle(callback(txn, "SUCCESS", "C2W-1", null));
    coreEvents.handle(event("WalletCredited", txn, txn + ":credit", "SUCCESS", null));

    assertThat(statuses(txn)).containsExactly("RECEIVED", "VALIDATING", "PARTNER_PENDING", "COMMITTING", "COMPLETED");
    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction", "CreditWallet");
    assertThat(last("mw.txn.status.v1", txn).path("partnerRef").asString()).isEqualTo("C2W-1");
    assertAllValid(txn);
  }

  @Test
  void anUnacknowledgedCreditIsResentWithTheSameCommandId() {
    String txn = toPartnerPending();
    inbound.handle(callback(txn, "SUCCESS", "C2W-1", null));

    clock.advance(Duration.ofSeconds(6));
    timers.sweep();

    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction", "CreditWallet", "CreditWallet");
    assertThat(outbox(COMMANDS, txn).stream().map(n -> n.path("commandId").asString()).skip(1).distinct())
        .containsExactly(txn + ":credit");
    assertThat(statuses(txn)).endsWith("COMMITTING");
  }

  @Test
  void aCoreRefusalToCreditGoesToManualReview() {
    String txn = toPartnerPending();
    inbound.handle(callback(txn, "SUCCESS", "C2W-1", null));

    coreEvents.handle(event("CreditRejected", txn, txn + ":credit", "FAILURE", "CREDIT_ACCOUNT_BLOCKED"));

    assertThat(statuses(txn)).endsWith("COMMITTING", "MANUAL_REVIEW");
    assertThat(last("mw.txn.status.v1", txn).path("error").path("code").asString())
        .isEqualTo("MW-CORE-CREDIT_ACCOUNT_BLOCKED");
    assertAllValid(txn);
  }

  @Test
  void aBankRefusalFailsWithNothingToRelease() {
    String txn = toPartnerPending();

    inbound.handle(callback(txn, "FAILED", "C2W-1", "MW-PTR-C2WBANK-DEBIT_REFUSED"));

    assertThat(statuses(txn)).endsWith("PARTNER_PENDING", "FAILED");
    assertThat(last("mw.txn.status.v1", txn).path("error").path("code").asString())
        .isEqualTo("MW-PTR-C2WBANK-DEBIT_REFUSED");
    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction");
    assertAllValid(txn);
  }

  @Test
  void aSynchronousRefusalFailsToo() {
    TxnRequest req = TestData.addMoneyBank(TestData.txnId(), "key-r", 50_000, ACCOUNT);
    transactions.accept(req, "gw-1");
    String txn = req.txnId();
    coreEvents.handle(event("TransactionValidated", txn, txn + ":validate", "SUCCESS", null));

    partnerReplies.handle(
        reply(txn, PartnerRequestMapper.requestId(txn, "PAY"), "PAY", "FAILED", "MW-PTR-C2WBANK-ACCOUNT_NOT_LINKED", null));

    assertThat(statuses(txn)).endsWith("PARTNER_PENDING", "FAILED");
    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction");
  }

  @Test
  void aValidationRefusalNeverAsksTheBank() {
    TxnRequest req = TestData.addMoneyBank(TestData.txnId(), "key-v", 50_000, ACCOUNT);
    transactions.accept(req, "gw-1");
    String txn = req.txnId();

    coreEvents.handle(event("TransactionRejected", txn, txn + ":validate", "FAILURE", "INVALID_PIN"));

    assertThat(statuses(txn)).endsWith("REJECTED");
    assertThat(outbox(PARTNER_TOPIC, txn)).isEmpty();
  }

  @Test
  void aBankTimeoutIsInquiredAndASuccessCreditsTheWallet() {
    String txn = toPartnerPending();
    clock.advance(Duration.ofSeconds(31)); // no callback within the partner deadline
    timers.sweep();
    assertThat(statuses(txn)).endsWith("UNKNOWN");

    clock.advance(Duration.ofSeconds(31));
    timers.sweep();
    JsonNode query = last(PARTNER_TOPIC, txn);
    assertThat(query.path("payload").path("operation").asString()).isEqualTo("STATUS_QUERY");
    assertThat(query.path("family").asString()).isEqualTo("bank");

    partnerReplies.handle(reply(txn, query.path("requestId").asString(), "STATUS_QUERY", "SUCCESS", null, "C2W-1"));

    assertThat(statuses(txn)).endsWith("UNKNOWN", "COMMITTING");
    assertThat(last(COMMANDS, txn).path("commandType").asString()).isEqualTo("CreditWallet");
    coreEvents.handle(event("WalletCredited", txn, txn + ":credit", "SUCCESS", null));
    assertThat(statuses(txn)).endsWith("COMPLETED");
    assertAllValid(txn);
  }

  @Test
  void aBankTimeoutWhoseInquiryFindsARefusalFails() {
    String txn = toPartnerPending();
    clock.advance(Duration.ofSeconds(31));
    timers.sweep();
    clock.advance(Duration.ofSeconds(31));
    timers.sweep();
    JsonNode query = last(PARTNER_TOPIC, txn);

    partnerReplies.handle(
        reply(txn, query.path("requestId").asString(), "STATUS_QUERY", "FAILED", "MW-PTR-C2WBANK-DEBIT_REFUSED", null));

    assertThat(statuses(txn)).endsWith("UNKNOWN", "FAILED");
    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction");
  }

  @Test
  void aLateSuccessCallbackAfterARefusalIsAConflictAndCreditsNothing() {
    String txn = toPartnerPending();
    inbound.handle(callback(txn, "FAILED", "C2W-1", "MW-PTR-C2WBANK-DEBIT_REFUSED"));

    inbound.handle(callback(txn, "SUCCESS", "C2W-1", null));

    assertThat(statuses(txn)).endsWith("FAILED");
    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction");
    assertThat(outbox("mw.audit.event.v1", txn).stream().map(n -> n.path("action").asString()))
        .contains("txn.outcome.conflict");
  }
}
