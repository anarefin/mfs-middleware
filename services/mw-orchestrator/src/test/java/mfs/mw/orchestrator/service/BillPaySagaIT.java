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
 * BILL_PAY (ticket 21, spec WP15) through the service seams: the TOPUP hold path with the billpay
 * family. Output is read from the outbox and validated against the owned contracts.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.orchestrator.consumers.enabled=false",
      "mw.orchestrator.timer.enabled=false"
    })
@Import({TestInfrastructure.class, SagaServiceIT.TestClock.class})
class BillPaySagaIT {

  private static final String STATUS_SCHEMA = "events/mw-orchestrator/mw.txn.status.v1.schema.json";
  private static final String PARTNER_SCHEMA = "events/mw-orchestrator/mw.partner.request.v1.schema.json";
  private static final String PARTNER_TOPIC = "mw.partner.request.billpay.v1";

  private final TransactionService transactions;
  private final CoreEventService coreEvents;
  private final PartnerReplyService partnerReplies;
  private final SagaTimerService timers;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  BillPaySagaIT(
      TransactionService transactions,
      CoreEventService coreEvents,
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
        "{\"schemaVersion\":1,\"family\":\"billpay\",\"requestId\":\"" + requestId + "\",\"txnId\":\"" + txnId
            + "\",\"correlationId\":\"corr-1\",\"partnerId\":\"billagg\",\"repliedAt\":\"2026-11-03T08:15:33Z\","
            + "\"payload\":" + payload + "}",
        PartnerReply.class);
  }

  private String toPartnerPending() {
    TxnRequest req = TestData.billPay(TestData.txnId(), "key-" + TestData.txnId(), 66_700, "1234567");
    transactions.accept(req, "gw-1");
    String txn = req.txnId();
    coreEvents.handle(event("TransactionValidated", txn, txn + ":validate", "{\"fee\":{\"valueMinor\":300,\"currency\":\"BDT\"}}"));
    coreEvents.handle(event("FundsHeld", txn, txn + ":hold", "{\"holdId\":\"HOLD-CORE-9\"}"));
    return txn;
  }

  @Test
  void aBillIsPaidOnTheBillpayTopicAndTheHoldCommitted() {
    String txn = toPartnerPending();
    JsonNode pay = last(PARTNER_TOPIC, txn);
    assertThat(pay.path("family").asString()).isEqualTo("billpay");
    assertThat(pay.path("payload").path("operation").asString()).isEqualTo("PAY");
    assertThat(pay.path("payload").path("txnType").asString()).isEqualTo("BILL_PAY");
    assertThat(pay.path("payload").path("partner").path("partnerId").asString()).isEqualTo("billagg");
    assertThat(pay.path("payload").path("partner").path("billerCode").asString()).isEqualTo("DESCO");
    assertThat(pay.path("payload").path("creditParty").path("type").asString()).isEqualTo("BILLER_ACCOUNT");
    assertThat(pay.path("payload").path("creditParty").path("id").asString()).isEqualTo("1234567");
    assertThat(pay.toString()).doesNotContain("encryptedPin");
    assertThat(outbox("mw.partner.request.telco.v1", txn)).isEmpty();

    partnerReplies.handle(reply(txn, PartnerRequestMapper.requestId(txn, "PAY"), "PAY", "SUCCESS", null, "AGG-7"));
    assertThat(last("core.payment.command.v1", txn).path("commandType").asString()).isEqualTo("CommitHold");
    coreEvents.handle(event("HoldCommitted", txn, txn + ":commit", "{\"holdId\":\"HOLD-CORE-9\"}"));

    assertThat(statuses(txn))
        .containsExactly("RECEIVED", "VALIDATING", "HOLDING", "PARTNER_PENDING", "COMMITTING", "COMPLETED");
    assertThat(last("mw.txn.status.v1", txn).path("partnerRef").asString()).isEqualTo("AGG-7");
    assertAllValid(txn);
  }

  @Test
  void aBillerRefusalReleasesTheHold() {
    String txn = toPartnerPending();
    partnerReplies.handle(
        reply(txn, PartnerRequestMapper.requestId(txn, "PAY"), "PAY", "FAILED", "MW-PTR-BILLAGG-PAYMENT_REFUSED", null));
    assertThat(last("core.payment.command.v1", txn).path("commandType").asString()).isEqualTo("ReleaseHold");
    coreEvents.handle(event("HoldReleased", txn, txn + ":release", "{\"holdId\":\"HOLD-CORE-9\"}"));

    assertThat(statuses(txn)).endsWith("RELEASING", "FAILED");
    assertThat(last("mw.txn.status.v1", txn).path("error").path("code").asString())
        .isEqualTo("MW-PTR-BILLAGG-PAYMENT_REFUSED");
    assertAllValid(txn);
  }

  @Test
  void anUnknownOutcomeIsInquiredOnTheBillpayTopicAndASuccessCommits() {
    String txn = toPartnerPending();
    partnerReplies.handle(
        reply(txn, PartnerRequestMapper.requestId(txn, "PAY"), "PAY", "UNKNOWN", "MW-TMO-PARTNER_TIMEOUT", null));
    assertThat(statuses(txn)).endsWith("UNKNOWN");

    clock.advance(Duration.ofSeconds(31));
    timers.sweep();
    JsonNode query = last(PARTNER_TOPIC, txn);
    assertThat(query.path("payload").path("operation").asString()).isEqualTo("STATUS_QUERY");
    assertThat(query.path("family").asString()).isEqualTo("billpay");

    partnerReplies.handle(
        reply(txn, query.path("requestId").asString(), "STATUS_QUERY", "SUCCESS", null, "AGG-8"));
    assertThat(statuses(txn)).endsWith("UNKNOWN", "COMMITTING");
    assertAllValid(txn);
  }
}
