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
import mfs.mw.orchestrator.dto.PartnerInbound;
import mfs.mw.orchestrator.dto.PartnerReply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * REMIT_IN (ticket 119b, FRS UC-16, FL-10, 5.3 inbound template): a REMITTANCE accepted by
 * mw-adapter-remit becomes a transaction under the adapter's txnId; the core validates the
 * beneficiary (exists, active, KYC and inbound limits), then CreditWallet from the UCB IMT settlement
 * wallet. The outcome goes back to the adapter as a PAY confirmation (family remit). Once accepted,
 * a remittance is never dropped: a refused credit ends in MANUAL_REVIEW.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.orchestrator.consumers.enabled=false",
      "mw.orchestrator.timer.enabled=false"
    })
@Import({TestInfrastructure.class, CoreCalls.class, SagaServiceIT.TestClock.class})
class RemitInFlowIT {

  private static final String STATUS_SCHEMA = "events/mw-orchestrator/mw.txn.status.v1.schema.json";
  private static final String PARTNER_SCHEMA = "events/mw-orchestrator/mw.partner.request.v1.schema.json";
  private static final String INBOUND_SCHEMA = "events/mw-orchestrator/mw.partner.inbound.v1.schema.json";
  private static final String CONFIRM_TOPIC = "mw.partner.request.remit.v1";

  private final PartnerInboundService inbound;
  private final PartnerReplyService partnerReplies;
  private final CoreCalls core;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  RemitInFlowIT(
      PartnerInboundService inbound,
      PartnerReplyService partnerReplies,
      CoreCalls core,
      Clock clock,
      JdbcTemplate jdbc,
      JsonMapper json) {
    this.inbound = inbound;
    this.partnerReplies = partnerReplies;
    this.core = core;
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

  private void assertAllValid(String txnId) {
    for (JsonNode n : outbox("mw.txn.status.v1", txnId)) {
      assertThat(ContractSchemas.schema(STATUS_SCHEMA).validate(n)).as(n.toString()).isEmpty();
    }
    for (JsonNode n : outbox(CONFIRM_TOPIC, txnId)) {
      assertThat(ContractSchemas.schema(PARTNER_SCHEMA).validate(n)).as(n.toString()).isEmpty();
    }
  }

  private CoreEvent event(String type, String txnId, String commandId, String outcome, String reason) {
    return json.readValue(
        "{\"schemaVersion\":1,\"eventType\":\"" + type + "\",\"eventId\":\"evt-" + type + "-" + commandId
            + "\",\"commandId\":\"" + commandId + "\",\"txnId\":\"" + txnId
            + "\",\"coreRef\":\"CORE-119\",\"occurredAt\":\"2026-11-03T08:15:31Z\",\"outcome\":\"" + outcome + "\""
            + (reason == null ? "" : ",\"reasonCode\":\"" + reason + "\"")
            + ",\"payload\":{}}",
        CoreEvent.class);
  }

  /** A remittance as mw-adapter-remit publishes it (inboundId stable per partner reference). */
  private PartnerInbound remittance(String txnId, String partnerRef, long valueMinor) {
    String inboundId = java.util.UUID.nameUUIDFromBytes(("remit:ucb-imt:" + partnerRef).getBytes()).toString();
    String body =
        "{\"schemaVersion\":1,\"family\":\"remit\",\"inboundId\":\"" + inboundId + "\",\"txnId\":\"" + txnId
            + "\",\"correlationId\":\"corr-remit\",\"partnerId\":\"ucb-imt\",\"partnerRef\":\"" + partnerRef
            + "\",\"receivedAt\":\"2026-11-03T08:15:29Z\",\"payload\":{\"kind\":\"REMITTANCE\",\"txnType\":\"REMIT_IN\","
            + "\"amount\":{\"valueMinor\":" + valueMinor + ",\"currency\":\"BDT\"},"
            + "\"creditParty\":{\"type\":\"WALLET\",\"id\":\"01711000001\",\"walletType\":\"CUSTOMER\"},"
            + "\"attributes\":{\"senderName\":\"J Doe\",\"senderCountry\":\"AE\"}}}";
    assertThat(ContractSchemas.schema(INBOUND_SCHEMA).validate(json.readTree(body))).as(body).isEmpty();
    return json.readValue(body, PartnerInbound.class);
  }

  private JsonNode confirmation(String txnId) {
    List<JsonNode> all = outbox(CONFIRM_TOPIC, txnId);
    assertThat(all).hasSize(1);
    return all.getFirst();
  }

  @Test
  void anAcceptedRemittanceIsValidatedThenCreditedFromTheSettlementWallet() {
    String txn = TestData.txnId();
    inbound.handle(remittance(txn, "IMT-1", 2_500_000));

    JsonNode validate = core.commands(txn).getFirst();
    assertThat(validate.path("commandType").asString()).isEqualTo("ValidateTransaction");
    assertThat(validate.path("payload").path("txnType").asString()).isEqualTo("REMIT_IN");
    assertThat(validate.path("payload").path("creditParty").path("id").asString()).isEqualTo("01711000001");
    assertThat(validate.path("payload").path("debitParty").path("type").asString()).isEqualTo("BANK_ACCOUNT");
    assertThat(validate.path("payload").path("debitParty").path("id").asString()).isEqualTo("ucb-imt");

    core.answer(event("TransactionValidated", txn, txn + ":validate", "SUCCESS", null));
    JsonNode credit = core.commands(txn).getLast();
    assertThat(credit.path("commandType").asString()).isEqualTo("CreditWallet");
    assertThat(credit.path("payload").path("settlementWallet").asString()).isEqualTo("SETTLEMENT_UCB_IMT");
    assertThat(credit.path("payload").path("amount").path("valueMinor").asLong()).isEqualTo(2_500_000);

    core.answer(event("WalletCredited", txn, txn + ":credit", "SUCCESS", null));
    assertThat(statuses(txn)).containsExactly("RECEIVED", "VALIDATING", "COMMITTING", "COMPLETED");
    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction", "CreditWallet");

    JsonNode confirm = confirmation(txn);
    assertThat(confirm.path("family").asString()).isEqualTo("remit");
    assertThat(confirm.path("payload").path("operation").asString()).isEqualTo("PAY");
    assertThat(confirm.path("payload").path("partnerTxnRef").asString()).isEqualTo("IMT-1");
    assertThat(confirm.path("payload").path("partner").path("partnerId").asString()).isEqualTo("ucb-imt");
    assertThat(confirm.path("payload").path("attributes").path("outcome").asString()).isEqualTo("CREDITED");
    assertThat(confirm.toString()).doesNotContain("J Doe");
    assertAllValid(txn);
  }

  @Test
  void aDuplicateRemittanceIsProcessedOnce() {
    String txn = TestData.txnId();
    inbound.handle(remittance(txn, "IMT-2", 100_000));
    inbound.handle(remittance(txn, "IMT-2", 100_000));

    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction");
    assertThat(statuses(txn)).containsExactly("RECEIVED", "VALIDATING");
    assertThat(jdbc.queryForObject("select count(*) from transaction", Integer.class)).isEqualTo(1);
  }

  @Test
  void anInactiveOrOverLimitBeneficiaryIsRefusedWithNoCredit() {
    String txn = TestData.txnId();
    inbound.handle(remittance(txn, "IMT-3", 100_000));
    core.answer(event("TransactionRejected", txn, txn + ":validate", "FAILURE", "LIMIT_EXCEEDED_PER_TXN"));

    assertThat(statuses(txn)).containsExactly("RECEIVED", "VALIDATING", "REJECTED");
    assertThat(commandTypes(txn)).containsExactly("ValidateTransaction");
    assertThat(confirmation(txn).path("payload").path("attributes").path("outcome").asString()).isEqualTo("REJECTED");
    assertAllValid(txn);
  }

  @Test
  void aCreditFailureAfterAcceptanceGoesToManualReview() {
    String txn = TestData.txnId();
    inbound.handle(remittance(txn, "IMT-4", 100_000));
    core.answer(event("TransactionValidated", txn, txn + ":validate", "SUCCESS", null));
    core.answer(event("CreditRejected", txn, txn + ":credit", "FAILURE", "CREDIT_ACCOUNT_BLOCKED"));

    assertThat(statuses(txn)).containsExactly("RECEIVED", "VALIDATING", "COMMITTING", "MANUAL_REVIEW");
    assertThat(confirmation(txn).path("payload").path("attributes").path("outcome").asString())
        .isEqualTo("MANUAL_REVIEW");
    assertAllValid(txn);
  }

  @Test
  void theAdaptersConfirmationReplyNeverChangesTheTransaction() {
    String txn = TestData.txnId();
    inbound.handle(remittance(txn, "IMT-5", 100_000));
    core.answer(event("TransactionValidated", txn, txn + ":validate", "SUCCESS", null));
    core.answer(event("WalletCredited", txn, txn + ":credit", "SUCCESS", null));
    String requestId = confirmation(txn).path("requestId").asString();

    partnerReplies.handle(json.readValue(
        "{\"schemaVersion\":1,\"family\":\"remit\",\"requestId\":\"" + requestId + "\",\"txnId\":\"" + txn
            + "\",\"correlationId\":\"corr-remit\",\"partnerId\":\"ucb-imt\",\"repliedAt\":\"2026-11-03T08:15:33Z\","
            + "\"payload\":{\"operation\":\"PAY\",\"outcome\":\"FAILED\",\"errorCode\":\"MW-PTR-UCBIMT-4001\","
            + "\"message\":\"Unknown\",\"retryable\":false}}",
        PartnerReply.class));

    assertThat(statuses(txn)).endsWith("COMPLETED");
    assertThat(statuses(txn)).hasSize(4);
  }
}
