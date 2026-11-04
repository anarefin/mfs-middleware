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
 * Partner callbacks on {@code mw.partner.inbound.v1} (ticket 22, spec 11.2 inbound path) through
 * the service seam: a callback resolves PARTNER_PENDING or UNKNOWN like a definite partner reply;
 * a callback for a transaction that already left those states changes nothing and is recorded
 * on the audit trail (a contradicting one as {@code txn.outcome.conflict}). Output is read from
 * the outbox.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "mw.orchestrator.consumers.enabled=false",
      "mw.orchestrator.timer.enabled=false"
    })
@Import({TestInfrastructure.class, SagaServiceIT.TestClock.class})
class PartnerCallbackSagaIT {

  private static final String STATUS_SCHEMA = "events/mw-orchestrator/mw.txn.status.v1.schema.json";
  private static final String INBOUND_SCHEMA = "events/mw-orchestrator/mw.partner.inbound.v1.schema.json";
  private static final String AUDIT_SCHEMA = "events/mw-audit-dw/mw.audit.event.v1.schema.json";

  private final TransactionService transactions;
  private final CoreEventService coreEvents;
  private final PartnerReplyService partnerReplies;
  private final PartnerInboundService callbacks;
  private final SagaTimerService timers;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  PartnerCallbackSagaIT(
      TransactionService transactions,
      CoreEventService coreEvents,
      PartnerReplyService partnerReplies,
      PartnerInboundService callbacks,
      SagaTimerService timers,
      Clock clock,
      JdbcTemplate jdbc,
      JsonMapper json) {
    this.transactions = transactions;
    this.coreEvents = coreEvents;
    this.partnerReplies = partnerReplies;
    this.callbacks = callbacks;
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

  private JsonNode last(String topic, String txnId) {
    List<JsonNode> all = outbox(topic, txnId);
    return all.get(all.size() - 1);
  }

  private List<JsonNode> auditsOtherThanStates(String txnId) {
    return outbox("mw.audit.event.v1", txnId).stream()
        .filter(a -> !"txn.state.changed".equals(a.path("action").asString()))
        .toList();
  }

  private void assertAllValid(String txnId) {
    for (JsonNode n : outbox("mw.txn.status.v1", txnId)) {
      assertThat(ContractSchemas.schema(STATUS_SCHEMA).validate(n)).as(n.toString()).isEmpty();
    }
    for (JsonNode n : outbox("mw.audit.event.v1", txnId)) {
      assertThat(ContractSchemas.schema(AUDIT_SCHEMA).validate(n)).as(n.toString()).isEmpty();
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

  private PartnerReply reply(String txnId, String requestId, String op, String outcome, String ref) {
    return json.readValue(
        "{\"schemaVersion\":1,\"family\":\"billpay\",\"requestId\":\"" + requestId + "\",\"txnId\":\"" + txnId
            + "\",\"correlationId\":\"corr-1\",\"partnerId\":\"billagg\",\"repliedAt\":\"2026-11-03T08:15:33Z\","
            + "\"payload\":{\"operation\":\"" + op + "\",\"outcome\":\"" + outcome + "\""
            + (ref == null ? "" : ",\"partnerRef\":\"" + ref + "\"") + "}}",
        PartnerReply.class);
  }

  /** A callback as mw-adapter-billpay publishes it; validated against the owned contract. */
  private PartnerInbound callback(String txnId, String outcome, String partnerRef, String errorCode) {
    String body = callbackBody(txnId, outcome, partnerRef, errorCode, "billagg", "billpay");
    assertThat(ContractSchemas.schema(INBOUND_SCHEMA).validate(json.readTree(body))).as(body).isEmpty();
    return json.readValue(body, PartnerInbound.class);
  }

  /** Any callback, also ones the contract or the transaction would not allow (not validated). */
  private PartnerInbound callback(
      String txnId, String outcome, String partnerRef, String errorCode, String partnerId, String family) {
    return json.readValue(callbackBody(txnId, outcome, partnerRef, errorCode, partnerId, family), PartnerInbound.class);
  }

  private String callbackBody(
      String txnId, String outcome, String partnerRef, String errorCode, String partnerId, String family) {
    String inboundId = UUID.nameUUIDFromBytes((partnerId + ":" + txnId + ":" + outcome + ":" + family).getBytes()).toString();
    String body =
        "{\"schemaVersion\":1,\"family\":\"" + family + "\",\"inboundId\":\"" + inboundId + "\",\"txnId\":\"" + txnId
            + "\",\"correlationId\":\"corr-cb\",\"partnerId\":\"" + partnerId + "\",\"partnerRef\":\"" + partnerRef
            + "\",\"receivedAt\":\"2026-11-03T08:15:40Z\",\"payload\":{\"kind\":\"CALLBACK\",\"outcome\":\""
            + outcome + "\",\"partnerCode\":\"" + ("FAILED".equals(outcome) ? "3003" : "000") + "\""
            + (errorCode == null ? "" : ",\"errorCode\":\"" + errorCode + "\"")
            + ",\"attributes\":{\"message\":\"from the aggregator\"}}}";
    return body;
  }

  private String toPartnerPending() {
    TxnRequest req = TestData.billPay(TestData.txnId(), "key-" + TestData.txnId(), 66_700, "1234567");
    transactions.accept(req, "gw-1");
    String txn = req.txnId();
    coreEvents.handle(event("TransactionValidated", txn, txn + ":validate", "{\"fee\":{\"valueMinor\":300,\"currency\":\"BDT\"}}"));
    coreEvents.handle(event("FundsHeld", txn, txn + ":hold", "{\"holdId\":\"HOLD-CORE-9\"}"));
    // the aggregator accepted the payment and will confirm it by callback
    partnerReplies.handle(reply(txn, PartnerRequestMapper.requestId(txn, "PAY"), "PAY", "PENDING", null));
    assertThat(statuses(txn)).endsWith("PARTNER_PENDING");
    return txn;
  }

  private String completed() {
    String txn = toPartnerPending();
    callbacks.handle(callback(txn, "SUCCESS", "AGG-7", null));
    coreEvents.handle(event("HoldCommitted", txn, txn + ":commit", "{\"holdId\":\"HOLD-CORE-9\"}"));
    assertThat(statuses(txn)).endsWith("COMPLETED");
    return txn;
  }

  // --- callback resolves the pending payment ------------------------------------------------

  @Test
  void aSuccessCallbackCommitsTheHoldAndKeepsThePartnerRef() {
    String txn = toPartnerPending();

    callbacks.handle(callback(txn, "SUCCESS", "AGG-7", null));

    assertThat(statuses(txn)).endsWith("PARTNER_PENDING", "COMMITTING");
    assertThat(last("core.payment.command.v1", txn).path("commandType").asString()).isEqualTo("CommitHold");
    assertThat(last("mw.txn.status.v1", txn).path("partnerRef").asString()).isEqualTo("AGG-7");
    assertAllValid(txn);
  }

  @Test
  void aFailedCallbackReleasesTheHoldWithThePartnersError() {
    String txn = toPartnerPending();

    callbacks.handle(callback(txn, "FAILED", "AGG-8", "MW-PTR-BILLAGG-PAYMENT_REFUSED"));

    assertThat(statuses(txn)).endsWith("PARTNER_PENDING", "RELEASING");
    assertThat(last("core.payment.command.v1", txn).path("commandType").asString()).isEqualTo("ReleaseHold");
    assertThat(last("mw.txn.status.v1", txn).path("error").path("code").asString())
        .isEqualTo("MW-PTR-BILLAGG-PAYMENT_REFUSED");
    assertAllValid(txn);
  }

  @Test
  void aPendingCallbackChangesNothing() {
    String txn = toPartnerPending();

    callbacks.handle(callback(txn, "PENDING", "AGG-9", null));

    assertThat(statuses(txn)).endsWith("PARTNER_PENDING");
    assertThat(auditsOtherThanStates(txn)).isEmpty();
  }

  @Test
  void aCallbackResolvesAnUnknownOutcome() {
    String txn = toPartnerPending();
    clock.advance(Duration.ofSeconds(31)); // no callback within the partner deadline
    timers.sweep();
    assertThat(statuses(txn)).endsWith("UNKNOWN");

    callbacks.handle(callback(txn, "SUCCESS", "AGG-10", null));

    assertThat(statuses(txn)).endsWith("UNKNOWN", "COMMITTING");
    assertThat(last("mw.txn.status.v1", txn).path("partnerRef").asString()).isEqualTo("AGG-10");
    assertAllValid(txn);
  }

  // --- duplicates and late callbacks ---------------------------------------------------------

  @Test
  void aDuplicateCallbackHasNoSecondEffect() {
    String txn = toPartnerPending();
    PartnerInbound cb = callback(txn, "SUCCESS", "AGG-7", null);

    callbacks.handle(cb);
    callbacks.handle(cb);

    assertThat(statuses(txn)).containsOnlyOnce("COMMITTING");
    assertThat(outbox("core.payment.command.v1", txn).stream().map(c -> c.path("commandType").asString()))
        .containsOnlyOnce("CommitHold");
    assertThat(auditsOtherThanStates(txn)).isEmpty();
  }

  @Test
  void aLateCallbackAgreeingWithTheResolutionIsRecordedAndChangesNothing() {
    String txn = completed();
    int statusCount = statuses(txn).size();

    callbacks.handle(callback(txn, "PENDING", "AGG-7", null)); // out of order: a stale PENDING
    callbacks.handle(callback(txn, "SUCCESS", "AGG-7", null)); // replayed: inbox drops it

    assertThat(statuses(txn)).hasSize(statusCount);
    List<JsonNode> audits = auditsOtherThanStates(txn);
    assertThat(audits).hasSize(1);
    JsonNode a = audits.getFirst();
    assertThat(a.path("action").asString()).isEqualTo("partner.callback.late");
    assertThat(a.path("outcome").asString()).isEqualTo("SUCCESS");
    assertThat(a.path("actor").path("type").asString()).isEqualTo("partner");
    assertThat(a.path("actor").path("id").asString()).isEqualTo("billagg");
    assertThat(a.path("after").path("status").asString()).isEqualTo("COMPLETED");
    assertThat(a.path("after").path("callbackOutcome").asString()).isEqualTo("PENDING");
    assertAllValid(txn);
  }

  @Test
  void aLateCallbackContradictingTheResolutionIsRecordedAsAConflictAndChangesNothing() {
    String txn = completed();
    int statusCount = statuses(txn).size();

    callbacks.handle(callback(txn, "FAILED", "AGG-7", "MW-PTR-BILLAGG-PAYMENT_REFUSED"));

    assertThat(statuses(txn)).hasSize(statusCount);
    assertThat(jdbc.queryForObject("select status from transaction where txn_id = ?::uuid", String.class, txn))
        .isEqualTo("COMPLETED");
    List<JsonNode> audits = auditsOtherThanStates(txn);
    assertThat(audits).hasSize(1);
    assertThat(audits.getFirst().path("action").asString()).isEqualTo("txn.outcome.conflict");
    assertThat(audits.getFirst().path("outcome").asString()).isEqualTo("FAILURE");
    assertThat(audits.getFirst().path("after").path("callbackOutcome").asString()).isEqualTo("FAILED");
    assertThat(audits.getFirst().path("after").path("errorCode").asString()).isEqualTo("MW-PTR-BILLAGG-PAYMENT_REFUSED");
    assertAllValid(txn);
  }

  @Test
  void aCallbackForAnUnknownTransactionIsDropped() {
    String txn = TestData.txnId();

    callbacks.handle(callback(txn, "SUCCESS", "AGG-1", null));

    assertThat(outbox("mw.audit.event.v1", txn)).isEmpty();
    assertThat(outbox("mw.txn.status.v1", txn)).isEmpty();
  }

  // --- a callback must belong to the transaction (security review) --------------------------

  private String status(String txnId) {
    return jdbc.queryForObject("select status from transaction where txn_id = ?::uuid", String.class, txnId);
  }

  private void assertRejected(String txn, String statusBefore, int statusRecords, String reason) {
    assertThat(status(txn)).isEqualTo(statusBefore);
    assertThat(statuses(txn)).hasSize(statusRecords);
    List<JsonNode> audits = auditsOtherThanStates(txn);
    assertThat(audits).hasSize(1);
    JsonNode a = audits.getFirst();
    assertThat(a.path("action").asString()).isEqualTo("partner.callback.rejected");
    assertThat(a.path("outcome").asString()).isEqualTo("DENIED");
    assertThat(a.path("after").path("reason").asString()).isEqualTo(reason);
    assertAllValid(txn);
  }

  @Test
  void aCallbackFromAnotherPartnerIsRejectedAndChangesNothing() {
    String txn = toPartnerPending();
    int before = statuses(txn).size();

    callbacks.handle(callback(txn, "SUCCESS", "OCS-1", null, "ocs", "billpay"));

    assertRejected(txn, "PARTNER_PENDING", before, "PARTNER_MISMATCH");
    assertThat(outbox("core.payment.command.v1", txn).stream().map(c -> c.path("commandType").asString()))
        .doesNotContain("CommitHold");
  }

  @Test
  void aCallbackOfAnotherFamilyIsRejectedAndChangesNothing() {
    String txn = toPartnerPending();
    int before = statuses(txn).size();

    callbacks.handle(callback(txn, "SUCCESS", "AGG-7", null, "billagg", "telco"));

    assertRejected(txn, "PARTNER_PENDING", before, "FAMILY_MISMATCH");
  }

  @Test
  void aCallbackNamingAnotherPartnerReferenceIsRejectedAndChangesNothing() {
    String txn = completed(); // partner_ref AGG-7 stored
    int before = statuses(txn).size();

    callbacks.handle(callback(txn, "FAILED", "AGG-99", "MW-PTR-BILLAGG-PAYMENT_REFUSED"));

    assertRejected(txn, "COMPLETED", before, "PARTNER_REF_MISMATCH");
  }

  @Test
  void aCallbackCarryingOurOwnReferenceStillMatchesAStoredPartnerRef() {
    String txn = completed();

    callbacks.handle(callback(txn, "FAILED", PartnerRequestMapper.partnerTxnRef(txn), "MW-PTR-BILLAGG-PAYMENT_REFUSED"));

    assertThat(auditsOtherThanStates(txn)).extracting(a -> a.path("action").asString())
        .containsExactly("txn.outcome.conflict");
  }

  @Test
  void aCallbackForATransactionWithoutAPartnerLegIsRejectedAndChangesNothing() {
    TxnRequest req = TestData.p2p(TestData.txnId(), "key-" + TestData.txnId(), 1_000);
    transactions.accept(req, "gw-1");
    String txn = req.txnId();
    String before = status(txn);
    int records = statuses(txn).size();

    callbacks.handle(callback(txn, "SUCCESS", "AGG-1", null, "billagg", "billpay"));

    assertRejected(txn, before, records, "NO_PARTNER_LEG");
  }

  @Test
  void aCallbackWithAnOutcomeOtherThanSuccessFailedPendingIsRejectedAndChangesNothing() {
    String txn = toPartnerPending();
    int before = statuses(txn).size();

    callbacks.handle(callback(txn, "UNKNOWN", "AGG-7", null, "billagg", "billpay"));

    assertRejected(txn, "PARTNER_PENDING", before, "UNKNOWN_OUTCOME");
  }
}
