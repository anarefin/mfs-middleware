package mfs.mw.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * MERCHANT_PAY to a merchant with its own system (ticket 115b, FRS UC-11, EXT-11): the merchant
 * system VALIDATEs before the core is asked (an invalid merchant fails before any money moves), the
 * payment posts, and the merchant is notified after COMPLETED; a failed notify is retried, then
 * dead-lettered, and the transaction stays COMPLETED. A merchant without a system is unchanged.
 * Output is read from the outbox and validated against the owned contracts.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.orchestrator.consumers.enabled=false",
      "mw.orchestrator.timer.enabled=false",
      "mw.orchestrator.merchant-systems.notify-attempts=3",
      "mw.orchestrator.merchant-systems.merchants.01911000009.partner-id=merchant-soap",
      "mw.orchestrator.merchant-systems.merchants.01911000009.merchant-id=M-0009",
      "mw.orchestrator.merchant-systems.merchants.01911000009.till-id=TILL-01"
    })
@Import({TestInfrastructure.class, CoreCalls.class, SagaServiceIT.TestClock.class})
class MerchantPayNotifyIT {

  private static final String PARTNER_SCHEMA = "events/mw-orchestrator/mw.partner.request.v1.schema.json";
  private static final String STATUS_SCHEMA = "events/mw-orchestrator/mw.txn.status.v1.schema.json";
  private static final String AUDIT_SCHEMA = "events/mw-audit-dw/mw.audit.event.v1.schema.json";
  private static final String TOPIC = "mw.partner.request.merchant.v1";
  private static final String EXTERNAL = "01911000009";
  private static final String PLAIN = "01911000001";

  private final TransactionService transactions;
  private final CoreCalls core;
  private final PartnerReplyService replies;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  MerchantPayNotifyIT(
      TransactionService transactions, CoreCalls core, PartnerReplyService replies, Clock clock,
      JdbcTemplate jdbc, JsonMapper json) {
    this.transactions = transactions;
    this.core = core;
    this.replies = replies;
    this.clock = (MutableClock) clock;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeEach
  void clean() {
    clock.set(TestData.NOW);
    for (String t : List.of("outbox", "inbox", "saga_timer", "saga_step", "idempotency", "partner_call", "transaction")) {
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
    return core.commands(txnId).stream().map(n -> n.path("commandType").asString()).toList();
  }

  private CoreEvent event(String type, String txnId, String commandId, String outcome, String reason) {
    return json.readValue(
        "{\"schemaVersion\":1,\"eventType\":\"" + type + "\",\"eventId\":\"evt-" + type + "-" + commandId
            + "\",\"commandId\":\"" + commandId + "\",\"txnId\":\"" + txnId
            + "\",\"coreRef\":\"CORE-115\",\"occurredAt\":\"2026-11-03T08:15:31Z\",\"outcome\":\"" + outcome + "\""
            + (reason == null ? "" : ",\"reasonCode\":\"" + reason + "\"")
            + ",\"payload\":{}}",
        CoreEvent.class);
  }

  private PartnerReply reply(String txnId, String requestKey, String op, String outcome, String errorCode) {
    String payload = "{\"operation\":\"" + op + "\",\"outcome\":\"" + outcome + "\""
        + (errorCode == null ? "" : ",\"errorCode\":\"" + errorCode + "\",\"message\":\"Refused\",\"retryable\":false")
        + ("SUCCESS".equals(outcome) ? ",\"partnerRef\":\"MREF-1\"" : "")
        + "}";
    return json.readValue(
        "{\"schemaVersion\":1,\"family\":\"merchant\",\"requestId\":\""
            + PartnerRequestMapper.requestId(txnId, requestKey) + "\",\"txnId\":\"" + txnId
            + "\",\"correlationId\":\"corr-1\",\"partnerId\":\"merchant-soap\",\"repliedAt\":\"2026-11-03T08:15:33Z\","
            + "\"payload\":" + payload + "}",
        PartnerReply.class);
  }

  private void assertValid(String txnId) {
    for (JsonNode n : outbox(TOPIC, txnId)) {
      assertThat(ContractSchemas.schema(PARTNER_SCHEMA).validate(n)).as(n.toString()).isEmpty();
    }
    for (JsonNode n : outbox("mw.txn.status.v1", txnId)) {
      assertThat(ContractSchemas.schema(STATUS_SCHEMA).validate(n)).as(n.toString()).isEmpty();
    }
    for (JsonNode n : outbox("mw.audit.event.v1", txnId)) {
      assertThat(ContractSchemas.schema(AUDIT_SCHEMA).validate(n)).as(n.toString()).isEmpty();
    }
  }

  private String accept(String merchant) {
    TxnRequest req = TestData.merchantPay(TestData.txnId(), "key-" + TestData.txnId(), 66_700, merchant);
    transactions.accept(req, "gw-1");
    return req.txnId();
  }

  /** Validated by the merchant system and the core, posted: COMPLETED with the first notify out. */
  private String completed() {
    String txn = accept(EXTERNAL);
    replies.handle(reply(txn, "INQUIRY", "INQUIRY", "SUCCESS", null));
    core.answer(event("TransactionValidated", txn, txn + ":validate", "SUCCESS", null));
    core.answer(event("TransferPosted", txn, txn + ":post", "SUCCESS", null));
    assertThat(statuses(txn)).endsWith("COMPLETED");
    return txn;
  }

  @Test
  void theMerchantSystemValidatesBeforeTheCoreIsAsked() {
    String txn = accept(EXTERNAL);

    List<JsonNode> sent = outbox(TOPIC, txn);
    assertThat(sent).hasSize(1);
    JsonNode validate = sent.getFirst();
    assertThat(validate.path("family").asString()).isEqualTo("merchant");
    assertThat(validate.path("requestId").asString()).isEqualTo(PartnerRequestMapper.requestId(txn, "INQUIRY"));
    assertThat(validate.path("payload").path("operation").asString()).isEqualTo("INQUIRY");
    assertThat(validate.path("payload").path("partner").path("partnerId").asString()).isEqualTo("merchant-soap");
    assertThat(validate.path("payload").path("creditParty").path("type").asString()).isEqualTo("MERCHANT");
    assertThat(validate.path("payload").path("creditParty").path("id").asString()).isEqualTo("M-0009");
    assertThat(validate.path("payload").path("attributes").path("tillId").asString()).isEqualTo("TILL-01");
    assertThat(validate.path("payload").has("amount")).isFalse();
    assertThat(commandTypes(txn)).isEmpty();
    assertThat(statuses(txn)).containsExactly("RECEIVED", "VALIDATING");

    replies.handle(reply(txn, "INQUIRY", "INQUIRY", "SUCCESS", null));

    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction");
    assertValid(txn);
  }

  @Test
  void anInvalidMerchantFailsAtValidateBeforeAnyMoneyMoves() {
    String txn = accept(EXTERNAL);

    replies.handle(reply(txn, "INQUIRY", "INQUIRY", "FAILED", "MW-PTR-MERCHANT_SOAP-MERCHANT_INACTIVE"));

    assertThat(statuses(txn)).containsExactly("RECEIVED", "VALIDATING", "FAILED");
    JsonNode failed = outbox("mw.txn.status.v1", txn).getLast();
    assertThat(failed.path("error").path("code").asString()).isEqualTo("MW-PTR-MERCHANT_SOAP-MERCHANT_INACTIVE");
    assertThat(commandTypes(txn)).isEmpty();
    assertThat(outbox(TOPIC, txn)).extracting(n -> n.path("payload").path("operation").asString())
        .containsExactly("INQUIRY");
    assertValid(txn);
  }

  @Test
  void anUnknownValidateAnswerFailsTooNothingHasMoved() {
    String txn = accept(EXTERNAL);

    replies.handle(reply(txn, "INQUIRY", "INQUIRY", "UNKNOWN", "MW-TMO-PARTNER_TIMEOUT"));

    assertThat(statuses(txn)).containsExactly("RECEIVED", "VALIDATING", "FAILED");
    assertThat(commandTypes(txn)).isEmpty();
  }

  @Test
  void aCompletedPaymentNotifiesTheMerchantOnce() {
    String txn = completed();

    List<JsonNode> sent = outbox(TOPIC, txn);
    assertThat(sent).extracting(n -> n.path("payload").path("operation").asString()).containsExactly("INQUIRY", "PAY");
    JsonNode notify = sent.getLast();
    assertThat(notify.path("requestId").asString()).isEqualTo(PartnerRequestMapper.requestId(txn, "NOTIFY:1"));
    assertThat(notify.path("payload").path("amount").path("valueMinor").asLong()).isEqualTo(66_700);
    assertThat(notify.path("payload").path("partnerTxnRef").asString())
        .isEqualTo(PartnerRequestMapper.partnerTxnRef(txn));

    replies.handle(reply(txn, "NOTIFY:1", "PAY", "SUCCESS", null));

    assertThat(outbox(TOPIC, txn)).hasSize(2);
    assertThat(statuses(txn)).containsExactly("RECEIVED", "VALIDATING", "POSTING", "COMPLETED");
    assertValid(txn);
  }

  @Test
  void aFailedNotifyIsRetriedThenDeadLetteredAndThePaymentStaysCompleted() {
    String txn = completed();

    replies.handle(reply(txn, "NOTIFY:1", "PAY", "UNKNOWN", "MW-TMO-PARTNER_TIMEOUT"));
    replies.handle(reply(txn, "NOTIFY:2", "PAY", "FAILED", "MW-PTR-MERCHANT_SOAP-UNMAPPED"));
    replies.handle(reply(txn, "NOTIFY:3", "PAY", "UNKNOWN", "MW-TMO-PARTNER_TIMEOUT"));

    assertThat(outbox(TOPIC, txn)).extracting(n -> n.path("requestId").asString()).containsExactly(
        PartnerRequestMapper.requestId(txn, "INQUIRY"),
        PartnerRequestMapper.requestId(txn, "NOTIFY:1"),
        PartnerRequestMapper.requestId(txn, "NOTIFY:2"),
        PartnerRequestMapper.requestId(txn, "NOTIFY:3"));
    assertThat(statuses(txn)).containsExactly("RECEIVED", "VALIDATING", "POSTING", "COMPLETED");
    List<JsonNode> deadLetter = outbox("mw.audit.event.v1", txn).stream()
        .filter(n -> "merchant.notify.failed".equals(n.path("action").asString()))
        .toList();
    assertThat(deadLetter).hasSize(1);
    assertThat(deadLetter.getFirst().path("after").path("attempts").asString()).isEqualTo("3");
    assertThat(deadLetter.getFirst().path("after").path("status").asString()).isEqualTo("COMPLETED");
    assertThat(deadLetter.getFirst().path("outcome").asString()).isEqualTo("FAILURE");
    assertValid(txn);
  }

  @Test
  void anAnswerToAnOlderNotifyAttemptDoesNotSendAnother() {
    String txn = completed();
    replies.handle(reply(txn, "NOTIFY:1", "PAY", "FAILED", "MW-PTR-MERCHANT_SOAP-UNMAPPED"));

    replies.handle(reply(txn, "NOTIFY:1", "PAY", "UNKNOWN", null));

    assertThat(outbox(TOPIC, txn)).hasSize(3); // INQUIRY, NOTIFY:1, NOTIFY:2
  }

  @Test
  void aMerchantWithoutItsOwnSystemIsValidateAndPostOnly() {
    String txn = accept(PLAIN);
    core.answer(event("TransactionValidated", txn, txn + ":validate", "SUCCESS", null));
    core.answer(event("TransferPosted", txn, txn + ":post", "SUCCESS", null));

    assertThat(statuses(txn)).containsExactly("RECEIVED", "VALIDATING", "POSTING", "COMPLETED");
    assertThat(outbox(TOPIC, txn)).isEmpty();
  }
}
