package mfs.mw.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import mfs.mw.orchestrator.CoreCalls;
import mfs.mw.orchestrator.MutableClock;
import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.TestInfrastructure;
import mfs.mw.orchestrator.dto.PartnerRef;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.messaging.ConfigChangedListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Biller amount rule (ticket 113c, FRS FL-04/FL-05): BILLER records of {@code mw.config.changed.v1}
 * land in {@code txn.biller_rule}; a bill amount the rule does not allow is REJECTED {@code
 * MW-VAL-032} before any core call, and one it allows goes on to validate.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"mw.orchestrator.consumers.enabled=false", "mw.orchestrator.timer.enabled=false"})
@Import({TestInfrastructure.class, CoreCalls.class, SagaServiceIT.TestClock.class})
class BillerAmountRuleIT {

  private final TransactionService transactions;
  private final ConfigChangedListener listener;
  private final BillerRules rules;
  private final CoreCalls coreEvents;
  private final MutableClock clock;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  BillerAmountRuleIT(
      TransactionService transactions, ConfigChangedListener listener, BillerRules rules, CoreCalls coreEvents,
      java.time.Clock clock, JdbcTemplate jdbc, JsonMapper json) {
    this.transactions = transactions;
    this.listener = listener;
    this.rules = rules;
    this.coreEvents = coreEvents;
    this.clock = (MutableClock) clock;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeEach
  void clean() {
    clock.set(TestData.NOW);
    for (String t : List.of("outbox", "inbox", "saga_timer", "saga_step", "idempotency", "transaction", "biller_rule")) {
      jdbc.update("delete from " + t);
    }
  }

  @Test
  void aBillerRecordIsStoredAndAnOlderVersionChangesNothing() {
    publish("AMBERIT", 2, "MINIMUM", 10_000L, null);
    publish("AMBERIT", 1, "OPEN", 1L, 2L);

    BillerRules.Rule rule = rules.find("AMBERIT").orElseThrow();
    assertThat(rule.amountRule()).isEqualTo("MINIMUM");
    assertThat(rule.minAmountMinor()).isEqualTo(10_000L);
    assertThat(rule.maxAmountMinor()).isNull();
    assertThat(rule.version()).isEqualTo(2);
  }

  @Test
  void aDeleteRemovesTheRule() {
    publish("AMBERIT", 1, "MINIMUM", 10_000L, null);
    listener.handle(record("biller/AMBERIT", "{\"schemaVersion\":1,\"configKey\":\"biller/AMBERIT\",\"configType\":\"BILLER\","
        + "\"change\":\"DELETE\",\"version\":2,\"changedAt\":\"2026-11-20T08:00:00Z\",\"changedBy\":\"m\",\"approvedBy\":\"c\","
        + "\"correlationId\":\"c-1\"}"), () -> {});

    assertThat(rules.find("AMBERIT")).isEmpty();
  }

  @Test
  void anAmountBelowTheMinimumIsRefusedBeforeAHoldAndOneAtItIsNot() {
    publish("AMBERIT", 1, "MINIMUM", 10_000L, null);

    String refused = accept(bill("AMBERIT", 9_999, null));
    assertThat(statuses(refused)).containsExactly("RECEIVED", "REJECTED");
    assertThat(lastStatus(refused).path("error").path("code").asString()).isEqualTo("MW-VAL-032");
    assertThat(commandTypes(refused)).isEmpty();
  }

  @Test
  void anOpenAmountOutsideItsRangeIsRefused() {
    publish("DESCO_PRE", 1, "OPEN", 10_000L, 5_000_000L);

    String refused = accept(bill("DESCO_PRE", 5_000_001, null));
    assertThat(lastStatus(refused).path("error").path("code").asString()).isEqualTo("MW-VAL-032");
    assertThat(commandTypes(refused)).isEmpty();
  }

  @Test
  void aFixedBillPaysOnlyTheFetchedAmount() {
    publish("DESCO", 1, "FIXED", null, null);

    String refused = accept(bill("DESCO", 66_000, Map.of("billOutstandingMinor", "66700")));
    assertThat(lastStatus(refused).path("error").path("code").asString()).isEqualTo("MW-VAL-032");
    assertThat(commandTypes(refused)).isEmpty();

    String paid = accept(bill("DESCO", 66_700, Map.of("billOutstandingMinor", "66700")));
    assertThat(statuses(paid)).containsExactly("RECEIVED", "VALIDATING");
    assertThat(commandTypes(paid)).containsExactly("ValidateTransaction");
  }

  private void publish(String code, long version, String rule, Long min, Long max) {
    String body = "{\"schemaVersion\":1,\"configKey\":\"biller/" + code + "\",\"configType\":\"BILLER\",\"change\":\"UPSERT\","
        + "\"version\":" + version + ",\"value\":{\"code\":\"" + code + "\",\"nameEn\":\"n\",\"nameBn\":\"n\",\"category\":\"OTHER\","
        + "\"primaryAggregator\":\"billagg\",\"fields\":[],\"amountRule\":\"" + rule + "\""
        + (min == null ? "" : ",\"minAmountMinor\":" + min) + (max == null ? "" : ",\"maxAmountMinor\":" + max)
        + ",\"billMonthRequired\":false,\"enabled\":true},\"changedAt\":\"2026-11-20T08:00:00Z\",\"changedBy\":\"m\","
        + "\"approvedBy\":\"c\",\"correlationId\":\"c-1\"}";
    listener.handle(record("biller/" + code, body), () -> {});
  }

  private static ConsumerRecord<String, String> record(String key, String body) {
    return new ConsumerRecord<>("mw.config.changed.v1", 0, 0, key, body);
  }

  private static TxnRequest bill(String biller, long amount, Map<String, String> attributes) {
    TxnRequest t = TestData.billPay(TestData.txnId(), "key-" + TestData.txnId(), amount, "1234567");
    return new TxnRequest(
        t.schemaVersion(), t.txnId(), t.idempotencyKey(), t.correlationId(), t.channel(), t.txnType(),
        t.initiator(), t.debitParty(), t.creditParty(), t.amount(), new PartnerRef("billagg", "BILL", biller),
        t.originalTxnId(), t.assertion(), t.locale(), t.metadata(), attributes, t.requestedAt());
  }

  private String accept(TxnRequest req) {
    transactions.accept(req, null);
    return req.txnId();
  }

  private List<JsonNode> outbox(String topic, String txnId) {
    return jdbc.queryForList(
            "select payload::text from outbox where topic = ? and msg_key = ? order by id", String.class, topic, txnId)
        .stream().map(json::readTree).toList();
  }

  private List<String> statuses(String txnId) {
    return outbox("mw.txn.status.v1", txnId).stream().map(n -> n.path("status").asString()).toList();
  }

  private JsonNode lastStatus(String txnId) {
    List<JsonNode> all = outbox("mw.txn.status.v1", txnId);
    return all.get(all.size() - 1);
  }

  private List<String> commandTypes(String txnId) {
    return coreEvents.commands(txnId).stream().map(c -> c.path("commandType").asString()).toList();
  }
}
