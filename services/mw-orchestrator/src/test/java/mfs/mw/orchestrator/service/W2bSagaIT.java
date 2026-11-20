package mfs.mw.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

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
 * W2B, transfer to bank (ticket 117c, FRS UC-15, FL-09) through the service seams: validate, hold,
 * PAY (credit the account) on the bank family to the routed {@code bank-<code>} profile, commit on the
 * bank's confirmation (callback), release on a refusal, STATUS_QUERY after an unknown outcome with no
 * second PAY. Output is read from the outbox and validated against the owned contracts.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.orchestrator.consumers.enabled=false",
      "mw.orchestrator.timer.enabled=false"
    })
@Import({TestInfrastructure.class, CoreCalls.class, SagaServiceIT.TestClock.class})
class W2bSagaIT {

  private static final String STATUS_SCHEMA = "events/mw-orchestrator/mw.txn.status.v1.schema.json";
  private static final String PARTNER_SCHEMA = "events/mw-orchestrator/mw.partner.request.v1.schema.json";
  private static final String PARTNER_TOPIC = "mw.partner.request.bank.v1";
  private static final String BANK = "bank-dbbl";
  private static final String ACCOUNT = "2050123456781234";

  private final TransactionService transactions;
  private final CoreCalls coreEvents;
  private final PartnerReplyService partnerReplies;
  private final PartnerInboundService inbound;
  private final SagaTimerService timers;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  W2bSagaIT(
      TransactionService transactions,
      CoreCalls coreEvents,
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
    return coreEvents.commands(txnId).stream().map(n -> n.path("commandType").asString()).toList();
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
  }

  private CoreEvent event(String type, String txnId, String commandId, String payload) {
    return json.readValue(
        "{\"schemaVersion\":1,\"eventType\":\"" + type + "\",\"eventId\":\"evt-" + type + "-" + commandId
            + "\",\"commandId\":\"" + commandId + "\",\"txnId\":\"" + txnId
            + "\",\"coreRef\":\"CORE-7\",\"occurredAt\":\"2026-11-03T08:15:31Z\",\"outcome\":\"SUCCESS\""
            + ",\"payload\":" + payload + "}",
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
            + "\",\"correlationId\":\"corr-1\",\"partnerId\":\"" + BANK + "\",\"repliedAt\":\"2026-11-03T08:15:33Z\","
            + "\"payload\":" + payload + "}",
        PartnerReply.class);
  }

  private PartnerInbound callback(String txnId, String outcome, String partnerRef) {
    String inboundId = UUID.nameUUIDFromBytes((BANK + ":" + txnId + ":" + outcome).getBytes()).toString();
    return json.readValue(
        "{\"schemaVersion\":1,\"family\":\"bank\",\"inboundId\":\"" + inboundId + "\",\"txnId\":\"" + txnId
            + "\",\"correlationId\":\"corr-cb\",\"partnerId\":\"" + BANK + "\",\"partnerRef\":\"" + partnerRef
            + "\",\"receivedAt\":\"2026-11-03T08:15:40Z\",\"payload\":{\"kind\":\"CALLBACK\",\"outcome\":\""
            + outcome + "\",\"partnerCode\":\"000\"}}",
        PartnerInbound.class);
  }

  /** Accepted, validated, held and the credit asked of the bank, which accepted it (PENDING). */
  private String toPartnerPending() {
    TxnRequest req = TestData.w2b(TestData.txnId(), "key-" + TestData.txnId(), 150_000, BANK, ACCOUNT);
    transactions.accept(req, "gw-1");
    String txn = req.txnId();
    coreEvents.answer(event("TransactionValidated", txn, txn + ":validate", "{\"fee\":{\"valueMinor\":0,\"currency\":\"BDT\"}}"));
    coreEvents.answer(event("FundsHeld", txn, txn + ":hold", "{\"holdId\":\"HOLD-W2B-1\"}"));
    partnerReplies.handle(reply(txn, PartnerRequestMapper.requestId(txn, "PAY"), "PAY", "PENDING", null, "DBBL-1"));
    assertThat(statuses(txn)).endsWith("PARTNER_PENDING");
    return txn;
  }

  @Test
  void theBankIsAskedToCreditTheAccountAfterTheHoldAndTheCallbackCommitsIt() {
    String txn = toPartnerPending();

    JsonNode pay = last(PARTNER_TOPIC, txn);
    assertThat(pay.path("family").asString()).isEqualTo("bank");
    assertThat(pay.path("payload").path("operation").asString()).isEqualTo("PAY");
    assertThat(pay.path("payload").path("txnType").asString()).isEqualTo("W2B");
    assertThat(pay.path("payload").path("partner").path("partnerId").asString()).isEqualTo(BANK);
    assertThat(pay.path("payload").path("debitParty").path("type").asString()).isEqualTo("WALLET");
    assertThat(pay.path("payload").path("creditParty").path("type").asString()).isEqualTo("BANK_ACCOUNT");
    assertThat(pay.path("payload").path("creditParty").path("id").asString()).isEqualTo(ACCOUNT);
    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction", "HoldFunds");

    inbound.handle(callback(txn, "SUCCESS", "DBBL-1"));
    // the bank replays its callback: dropped, no second commit
    inbound.handle(callback(txn, "SUCCESS", "DBBL-1"));
    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction", "HoldFunds", "CommitHold");
    coreEvents.answer(event("HoldCommitted", txn, txn + ":commit", "{\"holdId\":\"HOLD-W2B-1\"}"));

    assertThat(statuses(txn))
        .containsExactly("RECEIVED", "VALIDATING", "HOLDING", "PARTNER_PENDING", "COMMITTING", "COMPLETED");
    assertThat(last("mw.txn.status.v1", txn).path("partnerRef").asString()).isEqualTo("DBBL-1");
    assertAllValid(txn);
  }

  @Test
  void aBankRefusalReleasesTheHold() {
    String txn = toPartnerPending();
    partnerReplies.handle(
        reply(txn, PartnerRequestMapper.requestId(txn, "PAY"), "PAY", "FAILED", "MW-PTR-BANK-DBBL-ACCOUNT_CLOSED", null));
    assertThat(commandTypes(txn).getLast()).isEqualTo("ReleaseHold");
    coreEvents.answer(event("HoldReleased", txn, txn + ":release", "{\"holdId\":\"HOLD-W2B-1\"}"));

    assertThat(statuses(txn)).endsWith("RELEASING", "FAILED");
    assertThat(commandTypes(txn)).doesNotContain("CommitHold");
    assertAllValid(txn);
  }

  @Test
  void aTimeoutIsInquiredAndASuccessCommitsWithoutASecondPay() {
    String txn = toPartnerPending();
    partnerReplies.handle(
        reply(txn, PartnerRequestMapper.requestId(txn, "PAY"), "PAY", "UNKNOWN", "MW-TMO-PARTNER_TIMEOUT", null));
    assertThat(statuses(txn)).endsWith("UNKNOWN");

    clock.advance(Duration.ofSeconds(31));
    timers.sweep();
    JsonNode query = last(PARTNER_TOPIC, txn);
    assertThat(query.path("payload").path("operation").asString()).isEqualTo("STATUS_QUERY");
    assertThat(query.path("payload").path("partner").path("partnerId").asString()).isEqualTo(BANK);

    partnerReplies.handle(reply(txn, query.path("requestId").asString(), "STATUS_QUERY", "SUCCESS", null, "DBBL-2"));
    assertThat(statuses(txn)).endsWith("UNKNOWN", "COMMITTING");
    assertThat(outbox(PARTNER_TOPIC, txn).stream()
            .filter(n -> "PAY".equals(n.path("payload").path("operation").asString())))
        .hasSize(1);
    assertAllValid(txn);
  }

  @Test
  void aBankWithNoRoutingRuleIsRefusedBeforeAnyHold() {
    TxnRequest req = TestData.w2b(TestData.txnId(), "key-" + TestData.txnId(), 150_000, "bank-nowhere", ACCOUNT);
    transactions.accept(req, "gw-1");
    assertThat(statuses(req.txnId())).containsExactly("RECEIVED", "REJECTED");
    assertThat(last("mw.txn.status.v1", req.txnId()).path("error").path("code").asString()).isEqualTo("MW-PTR-UNKNOWN");
    assertThat(coreEvents.commands(req.txnId())).isEmpty();
  }

  @Test
  void aW2bToSomethingOtherThanABankAccountIsRefused() {
    TxnRequest ok = TestData.w2b(TestData.txnId(), "key-" + TestData.txnId(), 150_000, BANK, ACCOUNT);
    TxnRequest req = new TxnRequest(
        ok.schemaVersion(), ok.txnId(), ok.idempotencyKey(), ok.correlationId(), ok.channel(), ok.txnType(),
        ok.initiator(), ok.debitParty(), ok.debitParty(), ok.amount(), ok.partner(), ok.originalTxnId(),
        ok.assertion(), ok.locale(), ok.metadata(), ok.attributes(), ok.requestedAt());
    transactions.accept(req, "gw-1");
    assertThat(statuses(req.txnId())).containsExactly("RECEIVED", "REJECTED");
    assertThat(last("mw.txn.status.v1", req.txnId()).path("error").path("code").asString()).isEqualTo("MW-VAL-001");
    assertThat(coreEvents.commands(req.txnId())).isEmpty();
  }

  @Test
  void addMoneyFromALinkedAccountIsRoutedToItsBankProfile() {
    TxnRequest c2w = TestData.addMoneyBank(TestData.txnId(), "key-" + TestData.txnId(), 50_000, ACCOUNT);
    TxnRequest req = new TxnRequest(
        c2w.schemaVersion(), c2w.txnId(), c2w.idempotencyKey(), c2w.correlationId(), c2w.channel(), c2w.txnType(),
        c2w.initiator(), c2w.debitParty(), c2w.creditParty(), c2w.amount(),
        new mfs.mw.orchestrator.dto.PartnerRef(BANK, "C2W", null), c2w.originalTxnId(),
        c2w.assertion(), c2w.locale(), c2w.metadata(), c2w.attributes(), c2w.requestedAt());
    transactions.accept(req, "gw-1");
    coreEvents.answer(event("TransactionValidated", req.txnId(), req.txnId() + ":validate", "{}"));
    JsonNode pay = last(PARTNER_TOPIC, req.txnId());
    assertThat(pay.path("payload").path("partner").path("partnerId").asString()).isEqualTo(BANK);
    assertThat(pay.path("payload").path("debitParty").path("id").asString()).isEqualTo(ACCOUNT);
  }
}
