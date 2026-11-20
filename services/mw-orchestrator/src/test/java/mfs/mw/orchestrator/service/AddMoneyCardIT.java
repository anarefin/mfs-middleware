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
 * ADD_MONEY_CARD (ticket 118b, FRS UC-13, EXT-14, FL-07) through the service seams, T1-T12 where they
 * apply: validate, CREATE_SESSION, the 3-D Secure webhook, AUTHORISE_CAPTURE, CreditWallet from
 * SETTLEMENT_CYBERSOURCE. Output is read from the outbox and validated against the owned contracts; the
 * transient token must appear in the capture request and nowhere else.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.orchestrator.consumers.enabled=false",
      "mw.orchestrator.timer.enabled=false"
    })
@Import({TestInfrastructure.class, CoreCalls.class, SagaServiceIT.TestClock.class})
class AddMoneyCardIT {

  private static final String STATUS_SCHEMA = "events/mw-orchestrator/mw.txn.status.v1.schema.json";
  private static final String PARTNER_SCHEMA = "events/mw-orchestrator/mw.partner.request.v1.schema.json";
  private static final String INBOUND_SCHEMA = "events/mw-orchestrator/mw.partner.inbound.v1.schema.json";
  private static final String PARTNER_TOPIC = "mw.partner.request.card.v1";
  private static final String SESSION = "sess-0001";
  private static final String TOKEN = "tok-fake-0001";
  private static final String PAGE = "https://localhost/cybersource/hp/" + SESSION;

  private final TransactionService transactions;
  private final CoreCalls coreEvents;
  private final PartnerReplyService partnerReplies;
  private final PartnerInboundService inbound;
  private final SagaTimerService timers;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  AddMoneyCardIT(
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

  private List<String> operations(String txnId) {
    return outbox(PARTNER_TOPIC, txnId).stream().map(n -> n.path("payload").path("operation").asString()).toList();
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

  /** FL-07: the transient token is only in the capture request, never on a status, audit or the record. */
  private void assertTokenOnlyInTheCapture(String txnId) {
    for (String topic : List.of("mw.txn.status.v1", "mw.audit.event.v1")) {
      assertThat(outbox(topic, txnId).toString()).doesNotContain(TOKEN);
    }
    assertThat(jdbc.queryForObject("select row_to_json(t)::text from transaction t where txn_id = ?::uuid",
        String.class, txnId)).doesNotContain(TOKEN);
    assertThat(jdbc.queryForList("select row_to_json(p)::text from partner_call p where txn_id = ?::uuid",
        String.class, txnId).toString()).doesNotContain(TOKEN);
  }

  private CoreEvent event(String type, String txnId, String commandId, String outcome, String reason) {
    return json.readValue(
        "{\"schemaVersion\":1,\"eventType\":\"" + type + "\",\"eventId\":\"evt-" + type + "-" + commandId
            + "\",\"commandId\":\"" + commandId + "\",\"txnId\":\"" + txnId
            + "\",\"coreRef\":\"CORE-118\",\"occurredAt\":\"2026-11-03T08:15:31Z\",\"outcome\":\"" + outcome + "\""
            + (reason == null ? "" : ",\"reasonCode\":\"" + reason + "\"")
            + ",\"payload\":{}}",
        CoreEvent.class);
  }

  private PartnerReply reply(
      String txnId, String requestId, String op, String outcome, String errorCode, String ref, String attributes) {
    String payload =
        "{\"operation\":\"" + op + "\",\"outcome\":\"" + outcome + "\""
            + (errorCode == null ? "" : ",\"errorCode\":\"" + errorCode + "\",\"message\":\"Refused\",\"retryable\":false")
            + (ref == null ? "" : ",\"partnerRef\":\"" + ref + "\"")
            + (attributes == null ? "" : ",\"attributes\":" + attributes)
            + "}";
    return json.readValue(
        "{\"schemaVersion\":1,\"family\":\"card\",\"requestId\":\"" + requestId + "\",\"txnId\":\"" + txnId
            + "\",\"correlationId\":\"corr-1\",\"partnerId\":\"cybersource\",\"repliedAt\":\"2026-11-03T08:15:33Z\","
            + "\"payload\":" + payload + "}",
        PartnerReply.class);
  }

  private PartnerReply session(String txnId, String url) {
    return reply(txnId, PartnerRequestMapper.requestId(txnId, "INQUIRY"), "INQUIRY", "SUCCESS", null, SESSION,
        "{\"sessionId\":\"" + SESSION + "\",\"hostedPageUrl\":\"" + url + "\"}");
  }

  private PartnerReply capture(String txnId, String outcome, String errorCode) {
    return reply(txnId, PartnerRequestMapper.requestId(txnId, "PAY"), "PAY", outcome, errorCode,
        "SUCCESS".equals(outcome) ? "pay-0001" : null, "SUCCESS".equals(outcome) ? captured(txnId, "500.00") : null);
  }

  /** What the acquirer echoes on a capture (M9 review): amount, currency and our reference. */
  private static String captured(String txnId, String amountMajor) {
    return "{\"amount\":\"" + amountMajor + "\",\"currency\":\"BDT\",\"clientReference\":\""
        + PartnerRequestMapper.partnerTxnRef(txnId) + "\"}";
  }

  /** The 3-D Secure webhook as mw-adapter-card publishes it (inboundId from partner, reference, outcome). */
  private PartnerInbound webhook(String txnId, String outcome, String errorCode) {
    String reference = PartnerRequestMapper.partnerTxnRef(txnId);
    String inboundId = UUID.nameUUIDFromBytes(("cybersource:" + reference + ":" + outcome).getBytes()).toString();
    String attributes = "SUCCESS".equals(outcome)
        ? "{\"sessionId\":\"" + SESSION + "\",\"transientToken\":\"" + TOKEN + "\"}"
        : "{\"sessionId\":\"" + SESSION + "\"}";
    String body =
        "{\"schemaVersion\":1,\"family\":\"card\",\"inboundId\":\"" + inboundId + "\",\"txnId\":\"" + txnId
            + "\",\"correlationId\":\"corr-cb\",\"partnerId\":\"cybersource\",\"partnerRef\":\"" + SESSION
            + "\",\"receivedAt\":\"2026-11-03T08:15:40Z\",\"payload\":{\"kind\":\"CALLBACK\",\"outcome\":\""
            + outcome + "\",\"partnerCode\":\"" + ("FAILED".equals(outcome) ? "4002" : "000") + "\""
            + (errorCode == null ? "" : ",\"errorCode\":\"" + errorCode + "\"")
            + ",\"txnType\":\"ADD_MONEY_CARD\",\"attributes\":" + attributes + "}}";
    assertThat(ContractSchemas.schema(INBOUND_SCHEMA).validate(json.readTree(body))).as(body).isEmpty();
    return json.readValue(body, PartnerInbound.class);
  }

  /** Accepted and validated: the hosted page session is asked for. */
  private String toSessionRequested() {
    TxnRequest req = TestData.addMoneyCard(TestData.txnId(), "key-" + TestData.txnId(), 50_000);
    transactions.accept(req, "gw-1");
    String txn = req.txnId();
    coreEvents.answer(event("TransactionValidated", txn, txn + ":validate", "SUCCESS", null));
    assertThat(statuses(txn)).endsWith("PARTNER_PENDING");
    return txn;
  }

  /** The session is open and its URL went to the channel. */
  private String toSessionOpen() {
    String txn = toSessionRequested();
    partnerReplies.handle(session(txn, PAGE));
    return txn;
  }

  /** The webhook authenticated the card; the capture is out. */
  private String toCaptureSent() {
    String txn = toSessionOpen();
    inbound.handle(webhook(txn, "SUCCESS", null));
    assertThat(operations(txn)).containsExactly("INQUIRY", "PAY");
    return txn;
  }

  @Test
  void validationThenTheSessionIsAskedForWithoutCardDataOrToken() {
    String txn = toSessionRequested();

    JsonNode inquiry = last(PARTNER_TOPIC, txn);
    assertThat(inquiry.path("family").asString()).isEqualTo("card");
    assertThat(inquiry.path("requestId").asString()).isEqualTo(PartnerRequestMapper.requestId(txn, "INQUIRY"));
    assertThat(inquiry.path("payload").path("operation").asString()).isEqualTo("INQUIRY");
    assertThat(inquiry.path("payload").path("txnType").asString()).isEqualTo("ADD_MONEY_CARD");
    assertThat(inquiry.path("payload").path("partner").path("partnerId").asString()).isEqualTo("cybersource");
    assertThat(inquiry.path("payload").path("amount").path("valueMinor").asLong()).isEqualTo(50_000);
    assertThat(inquiry.path("payload").has("attributes")).isFalse();
    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction");
    assertAllValid(txn);
  }

  @Test
  void t1TheSessionUrlGoesToTheChannelAndASuccessfulCaptureCreditsFromTheCardSettlementWallet() {
    String txn = toSessionOpen();
    JsonNode opened = last("mw.txn.status.v1", txn);
    assertThat(opened.path("status").asString()).isEqualTo("PARTNER_PENDING");
    assertThat(opened.path("attributes").path("hostedPageUrl").asString()).isEqualTo(PAGE);
    assertThat(opened.path("attributes").path("sessionId").asString()).isEqualTo(SESSION);

    inbound.handle(webhook(txn, "SUCCESS", null));
    JsonNode pay = last(PARTNER_TOPIC, txn);
    assertThat(pay.path("payload").path("operation").asString()).isEqualTo("PAY");
    assertThat(pay.path("requestId").asString()).isEqualTo(PartnerRequestMapper.requestId(txn, "PAY"));
    assertThat(pay.path("payload").path("attributes").path("sessionId").asString()).isEqualTo(SESSION);
    assertThat(pay.path("payload").path("attributes").path("transientToken").asString()).isEqualTo(TOKEN);
    assertThat(pay.path("payload").path("debitParty").path("type").asString()).isEqualTo("CARD_TOKEN");
    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction"); // nothing credited before capture

    partnerReplies.handle(capture(txn, "SUCCESS", null));
    JsonNode credit = coreEvents.commands(txn).getLast();
    assertThat(credit.path("commandType").asString()).isEqualTo("CreditWallet");
    assertThat(credit.path("payload").path("settlementWallet").asString()).isEqualTo("SETTLEMENT_CYBERSOURCE");
    assertThat(credit.path("payload").path("creditParty").path("id").asString()).isEqualTo("01711000001");
    assertThat(credit.path("payload").path("amount").path("valueMinor").asLong()).isEqualTo(50_000);
    coreEvents.answer(event("WalletCredited", txn, txn + ":credit", "SUCCESS", null));

    assertThat(statuses(txn))
        .containsExactly("RECEIVED", "VALIDATING", "PARTNER_PENDING", "PARTNER_PENDING", "COMMITTING", "COMPLETED");
    assertThat(last("mw.txn.status.v1", txn).path("partnerRef").asString()).isEqualTo("pay-0001");
    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction", "CreditWallet");
    assertTokenOnlyInTheCapture(txn);
    assertAllValid(txn);
  }

  @Test
  void t2ADeclineFailsWithNothingCredited() {
    String txn = toCaptureSent();

    partnerReplies.handle(capture(txn, "FAILED", "MW-PTR-CYBERSOURCE-CARD_DECLINED"));

    assertThat(statuses(txn)).endsWith("PARTNER_PENDING", "FAILED");
    assertThat(last("mw.txn.status.v1", txn).path("error").path("code").asString())
        .isEqualTo("MW-PTR-CYBERSOURCE-CARD_DECLINED");
    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction");
    assertTokenOnlyInTheCapture(txn);
  }

  @Test
  void aThreeDSecureFailureFailsWithoutACapture() {
    String txn = toSessionOpen();

    inbound.handle(webhook(txn, "FAILED", "MW-PTR-CYBERSOURCE-THREE_DS_FAILED"));

    assertThat(statuses(txn)).endsWith("FAILED");
    assertThat(last("mw.txn.status.v1", txn).path("error").path("code").asString())
        .isEqualTo("MW-PTR-CYBERSOURCE-THREE_DS_FAILED");
    assertThat(operations(txn)).containsExactly("INQUIRY");
    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction");
  }

  @Test
  void t3t4ACaptureTimeoutIsInquiredNeverCreditedBlind() {
    String txn = toCaptureSent();
    partnerReplies.handle(capture(txn, "UNKNOWN", "MW-TMO-PARTNER_TIMEOUT"));
    assertThat(statuses(txn)).endsWith("UNKNOWN");
    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction");

    clock.advance(Duration.ofSeconds(31));
    timers.sweep();
    JsonNode query = last(PARTNER_TOPIC, txn);
    assertThat(query.path("payload").path("operation").asString()).isEqualTo("STATUS_QUERY");
    assertThat(query.toString()).doesNotContain(TOKEN);

    partnerReplies.handle(reply(txn, query.path("requestId").asString(), "STATUS_QUERY", "SUCCESS", null, "pay-0001",
        captured(txn, "500.00")));
    assertThat(statuses(txn)).endsWith("UNKNOWN", "COMMITTING");
    assertThat(coreEvents.commands(txn).getLast().path("commandType").asString()).isEqualTo("CreditWallet");
    assertAllValid(txn);
  }

  @Test
  void t4ACaptureTimeoutWhoseInquiryFindsADeclineFails() {
    String txn = toCaptureSent();
    clock.advance(Duration.ofSeconds(31)); // no capture answer within the partner deadline
    timers.sweep();
    assertThat(statuses(txn)).endsWith("UNKNOWN");
    clock.advance(Duration.ofSeconds(31));
    timers.sweep();
    JsonNode query = last(PARTNER_TOPIC, txn);

    partnerReplies.handle(reply(txn, query.path("requestId").asString(), "STATUS_QUERY", "FAILED",
        "MW-PTR-CYBERSOURCE-CARD_DECLINED", null, null));

    assertThat(statuses(txn)).endsWith("UNKNOWN", "FAILED");
    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction");
  }

  @Test
  void aCaptureOfAnotherAmountIsNeverCredited() {
    String txn = toCaptureSent();

    partnerReplies.handle(reply(txn, PartnerRequestMapper.requestId(txn, "PAY"), "PAY", "SUCCESS", null, "pay-0001",
        captured(txn, "5000.00")));

    assertThat(statuses(txn)).endsWith("PARTNER_PENDING", "UNKNOWN");
    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction");

    clock.advance(Duration.ofSeconds(31));
    timers.sweep();
    JsonNode query = last(PARTNER_TOPIC, txn);
    partnerReplies.handle(reply(txn, query.path("requestId").asString(), "STATUS_QUERY", "SUCCESS", null, "pay-0001",
        null)); // no echo: still not credited
    assertThat(statuses(txn)).endsWith("UNKNOWN");
    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction");
  }

  @Test
  void t7ADuplicateWebhookCapturesOnce() {
    String txn = toCaptureSent();

    inbound.handle(webhook(txn, "SUCCESS", null)); // same inboundId: the inbox drops it

    assertThat(operations(txn)).containsExactly("INQUIRY", "PAY");
    assertThat(statuses(txn)).endsWith("PARTNER_PENDING");
  }

  @Test
  void aWebhookIsNeverReadAsTheCapturesOutcome() {
    String txn = toCaptureSent();
    partnerReplies.handle(capture(txn, "UNKNOWN", "MW-TMO-PARTNER_TIMEOUT"));

    // a replayed authentication under another inbound id while the capture is unknown: no credit
    PartnerInbound again = webhook(txn, "SUCCESS", null);
    inbound.handle(new PartnerInbound(again.schemaVersion(), again.family(), UUID.randomUUID().toString(),
        again.txnId(), again.correlationId(), again.partnerId(), again.partnerRef(), again.receivedAt(),
        again.payload()));

    assertThat(statuses(txn)).endsWith("UNKNOWN");
    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction");
    assertThat(operations(txn)).containsExactly("INQUIRY", "PAY");
  }

  @Test
  void aWebhookBeforeTheSessionIsOpenCapturesNothing() {
    String txn = toSessionRequested();

    inbound.handle(webhook(txn, "SUCCESS", null));

    assertThat(operations(txn)).containsExactly("INQUIRY");
    assertThat(statuses(txn)).endsWith("PARTNER_PENDING");
  }

  @Test
  void aHostedPageOnAnotherHostOrSchemeIsRefused() {
    String txn = toSessionRequested();

    partnerReplies.handle(session(txn, "javascript:alert(1)"));

    assertThat(statuses(txn)).endsWith("FAILED");
    assertThat(last("mw.txn.status.v1", txn).path("error").path("code").asString())
        .isEqualTo("MW-PTR-CYBERSOURCE-MALFORMED_REPLY");
    assertThat(outbox("mw.txn.status.v1", txn).toString()).doesNotContain("javascript");
    assertThat(operations(txn)).containsExactly("INQUIRY");
  }

  @Test
  void aSessionRefusalFailsWithNothingMoved() {
    String txn = toSessionRequested();

    partnerReplies.handle(reply(txn, PartnerRequestMapper.requestId(txn, "INQUIRY"), "INQUIRY", "FAILED",
        "MW-PTR-CYBERSOURCE-SESSION_EXPIRED", null, null));

    assertThat(statuses(txn)).endsWith("PARTNER_PENDING", "FAILED");
    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction");
  }

  @Test
  void theSessionWaitsLongerThanThePartnerDeadlineForTheCustomer() {
    String txn = toSessionOpen();

    clock.advance(Duration.ofSeconds(31));
    timers.sweep();
    assertThat(statuses(txn)).last().isEqualTo("PARTNER_PENDING");

    clock.advance(Duration.ofMinutes(15));
    timers.sweep();
    assertThat(statuses(txn)).endsWith("UNKNOWN"); // then STATUS_QUERY: no payment -> FAILED
    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction");
  }

  @Test
  void aCardRequestThatIsNotATokenToAWalletIsRejected() {
    TxnRequest card = TestData.addMoneyCard(TestData.txnId(), "key-bad", 50_000);
    TxnRequest bad = new TxnRequest(card.schemaVersion(), card.txnId(), card.idempotencyKey(), card.correlationId(),
        card.channel(), card.txnType(), card.initiator(), new mfs.mw.orchestrator.dto.Party("WALLET", "01711000002", null, "CUSTOMER"),
        card.creditParty(), card.amount(), card.partner(), card.originalTxnId(), card.assertion(), card.locale(),
        card.metadata(), card.attributes(), card.requestedAt(), card.confirmDuplicate());
    transactions.accept(bad, "gw-1");

    assertThat(statuses(bad.txnId())).endsWith("REJECTED");
    assertThat(last("mw.txn.status.v1", bad.txnId()).path("error").path("code").asString()).isEqualTo("MW-VAL-001");
    assertThat(outbox(PARTNER_TOPIC, bad.txnId())).isEmpty();
  }
}
