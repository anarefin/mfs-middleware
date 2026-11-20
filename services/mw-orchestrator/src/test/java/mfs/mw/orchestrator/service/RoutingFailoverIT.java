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
import mfs.mw.orchestrator.dto.CoreEvent;
import mfs.mw.orchestrator.dto.PartnerRef;
import mfs.mw.orchestrator.dto.PartnerReply;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.mapper.PartnerRequestMapper;
import mfs.mw.orchestrator.service.routing.RoutingCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Failover only before money leaves (ticket 98). A definite pre-send refusal is tried on the next
 * connector. A partner that never answers stays UNKNOWN on the connector that was asked.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"mw.orchestrator.consumers.enabled=false", "mw.orchestrator.timer.enabled=false"})
@Import({TestInfrastructure.class, CoreCalls.class, SagaServiceIT.TestClock.class})
class RoutingFailoverIT {

  private static final String PARTNER_TOPIC = "mw.partner.request.telco.v1";

  private final TransactionService transactions;
  private final CoreCalls coreEvents;
  private final PartnerReplyService partnerReplies;
  private final SagaTimerService timers;
  private final RoutingCatalog catalog;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  RoutingFailoverIT(
      TransactionService transactions,
      CoreCalls coreEvents,
      PartnerReplyService partnerReplies,
      SagaTimerService timers,
      RoutingCatalog catalog,
      Clock clock,
      JdbcTemplate jdbc,
      JsonMapper json) {
    this.transactions = transactions;
    this.coreEvents = coreEvents;
    this.partnerReplies = partnerReplies;
    this.timers = timers;
    this.catalog = catalog;
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
    restore();
  }

  @AfterEach
  void restore() {
    jdbc.update("update routing_rule set fallbacks = '{}', version = 1 where id = 'topup-gp'");
    catalog.noteHealth("ocs", null);
    catalog.noteHealth("ocs-standby", null);
    catalog.reload();
  }

  @Test
  void anOpenCircuitReplyFailsOverToTheFallback() {
    jdbc.update("update routing_rule set fallbacks = '{ocs-standby}' where id = 'topup-gp'");
    catalog.reload();
    String txn = startToPartnerPending(2_100);
    assertThat(partnerIds(txn)).containsExactly("gp");

    partnerReplies.handle(reply(txn, PartnerRequestMapper.PAY, "FAILED", "MW-SYS-PARTNER_CIRCUIT_OPEN"));

    assertThat(status(txn)).isEqualTo("PARTNER_PENDING");
    assertThat(partnerIds(txn)).containsExactly("gp", "ocs-standby");
    String failoverId = PartnerRequestMapper.requestId(txn, "PAY:ocs-standby");
    assertThat(requestIds(txn)).containsExactly(PartnerRequestMapper.requestId(txn, "PAY"), failoverId);

    partnerReplies.handle(reply(txn, "PAY:ocs-standby", "SUCCESS", null));
    assertThat(status(txn)).isEqualTo("COMMITTING");
  }

  @Test
  void aDownPrimaryIsSkippedAndTheFallbackGetsThePay() {
    jdbc.update("update routing_rule set fallbacks = '{ocs-standby}' where id = 'topup-gp'");
    catalog.reload();
    catalog.noteHealth("ocs", "DOWN");
    String txn = startToPartnerPending(2_200);

    assertThat(partnerIds(txn)).containsExactly("ocs-standby");
    assertThat(requestIds(txn)).containsExactly(PartnerRequestMapper.requestId(txn, "PAY"));
    assertThat(status(txn)).isEqualTo("PARTNER_PENDING");
  }

  @Test
  void noFallbackFailsFastWithTheCircuitOpen() {
    catalog.noteHealth("ocs", "DOWN");
    TxnRequest req = TestData.topup(TestData.txnId(), "key-down-" + UUID.randomUUID(), 2_300, "01711000002");
    transactions.accept(req, null);

    assertThat(statuses(req.txnId())).containsExactly("RECEIVED", "REJECTED");
    assertThat(lastStatus(req.txnId()).path("error").path("code").asString()).isEqualTo("MW-SYS-PARTNER_CIRCUIT_OPEN");
    assertThat(outbox(PARTNER_TOPIC, req.txnId())).isEmpty();
  }

  @Test
  void anAmbiguousPayTimeoutNeverFailsOver() {
    jdbc.update("update routing_rule set fallbacks = '{ocs-standby}' where id = 'topup-gp'");
    catalog.reload();
    String txn = startToPartnerPending(2_400);
    clock.advance(Duration.ofSeconds(31));
    assertThat(timers.sweep()).isEqualTo(1);

    assertThat(status(txn)).isEqualTo("UNKNOWN");
    assertThat(partnerIds(txn)).containsExactly("gp");
  }

  @Test
  void aTopupWithoutAPartnerIdUsesThePrefix() {
    TxnRequest t = TestData.topup(TestData.txnId(), "key-prefix-" + UUID.randomUUID(), 2_500, "01711000002");
    TxnRequest routed =
        new TxnRequest(
            1, t.txnId(), t.idempotencyKey(), t.correlationId(), t.channel(), "TOPUP", t.initiator(),
            t.debitParty(), t.creditParty(), t.amount(), new PartnerRef(null, "PREPAID", null), null,
            t.assertion(), null, null, null, t.requestedAt());
    transactions.accept(routed, null);
    assertThat(statuses(t.txnId())).containsExactly("RECEIVED", "VALIDATING");
    assertThat(lastStatus(t.txnId()).path("partner").path("partnerId").asString()).isEqualTo("ocs");
  }

  private String startToPartnerPending(long amount) {
    String txn = TestData.txnId();
    transactions.accept(TestData.topup(txn, "key-" + txn, amount, "01711000002"), "gw-1");
    coreEvents.answer(event("TransactionValidated", txn, txn + ":validate", "{\"fee\":{\"valueMinor\":200,\"currency\":\"BDT\"}}"));
    coreEvents.answer(event("FundsHeld", txn, txn + ":hold", "{\"holdId\":\"HOLD-CORE-7\"}"));
    return txn;
  }

  private CoreEvent event(String type, String txnId, String commandId, String payload) {
    return json.readValue(
        "{\"schemaVersion\":1,\"eventType\":\"" + type + "\",\"eventId\":\"evt-" + type + "-" + commandId
            + "\",\"commandId\":\"" + commandId + "\",\"txnId\":\"" + txnId
            + "\",\"coreRef\":\"CORE-7\",\"occurredAt\":\"2026-11-03T08:15:31Z\",\"outcome\":\"SUCCESS\",\"payload\":"
            + payload + "}",
        CoreEvent.class);
  }

  private PartnerReply reply(String txnId, String operation, String outcome, String errorCode) {
    String payload =
        "{\"operation\":\"PAY\",\"outcome\":\"" + outcome + "\""
            + (errorCode == null ? "" : ",\"errorCode\":\"" + errorCode + "\",\"message\":\"not sent\",\"retryable\":true")
            + "}";
    return json.readValue(
        "{\"schemaVersion\":1,\"family\":\"telco\",\"requestId\":\""
            + PartnerRequestMapper.requestId(txnId, operation) + "\",\"txnId\":\"" + txnId
            + "\",\"correlationId\":\"corr-1\",\"partnerId\":\"gp\",\"repliedAt\":\"2026-11-03T08:15:33Z\",\"payload\":"
            + payload + "}",
        PartnerReply.class);
  }

  private List<JsonNode> outbox(String topic, String txnId) {
    return jdbc.queryForList(
            "select payload::text from outbox where topic = ? and msg_key = ? order by id", String.class, topic, txnId)
        .stream().map(json::readTree).toList();
  }

  private List<String> partnerIds(String txnId) {
    return outbox(PARTNER_TOPIC, txnId).stream().map(n -> n.path("payload").path("partner").path("partnerId").asString()).toList();
  }

  private List<String> requestIds(String txnId) {
    return outbox(PARTNER_TOPIC, txnId).stream().map(n -> n.path("requestId").asString()).toList();
  }

  private List<String> statuses(String txnId) {
    return outbox("mw.txn.status.v1", txnId).stream().map(n -> n.path("status").asString()).toList();
  }

  private JsonNode lastStatus(String txnId) {
    List<JsonNode> all = outbox("mw.txn.status.v1", txnId);
    return all.get(all.size() - 1);
  }

  private String status(String txnId) {
    return jdbc.queryForObject("select status from transaction where txn_id = ?::uuid", String.class, txnId);
  }
}
